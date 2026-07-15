(ns blog.grays.web.micropub
  "A minimal Micropub endpoint (https://micropub.spec.indieweb.org/).

  Only entry creation is supported: a successful POST writes a markdown file
  into the :posts-dir, after which the file watcher syncs it into the content
  db like any hand-written post — including any Webmention/WebSub
  notifications. Authentication is delegated: bearer tokens are verified
  against the :indieauth token endpoint and must have been issued for the
  blog's own domain."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [jsonista.core :as json]
            [sluj.core :refer [sluj]]
            [taoensso.telemere :as tel]
            [blog.grays.web.db :as db]
            [blog.grays.web.component :as c]
            [blog.grays.web.http :as http]
            [blog.grays.web.shared :as shared])
  (:import [java.time LocalDate]))

;;; Responses

(defn- json-response
  [status body]
  {:status  status
   :headers {"Content-Type" "application/json"}
   :body    (json/write-value-as-string body)})

(def ^:private error-status
  {:invalid_request    400
   :unauthorized       401
   :forbidden          403
   :insufficient_scope 403})

(defn- error-response
  "An `error` response (a keyword from the spec) with a human `description`."
  [error description]
  (json-response (error-status error)
                 {:error             (name error)
                  :error_description description}))

;;; Delegated authentication

(defn- verify-token
  "Verify bearer `token` against `token-endpoint`; returns the token info
  (:me, :scope, ...) when the endpoint accepts it, nil otherwise."
  [token-endpoint token]
  (try
    (let [{:keys [status body]} (http/GET token-endpoint
                                          {"Authorization" (str "Bearer " token)
                                           "Accept"        "application/json"})]
      (when (= 200 status)
        (json/read-value body json/keyword-keys-object-mapper)))
    (catch Exception e
      (tel/log! {:level :warn
                 :id    ::token-verification-error
                 :data  {:endpoint token-endpoint :error (str e)}
                 :msg   (str "Could not verify token: " e)}))))

(defn- request-token
  "The bearer token of `req`: Authorization header or access_token param."
  [{:keys [headers form-params] :as req}]
  (or (some->> (get headers "authorization")
               (re-find #"(?i)^Bearer +(\S+)$")
               (second))
      (:access_token form-params)))

(defn- authorize
  "Authorize `req` against the delegated token endpoint; returns an error
  response to short-circuit with, or nil when access is granted. The token
  must belong to this site and — when `required-scopes` is non-nil — grant
  at least one of those scopes."
  [{:keys [conf] :as req} required-scopes]
  (if-let [token (request-token req)]
    (let [endpoint (get-in conf [:indieauth :token-endpoint])
          {:keys [me scope] :as info} (verify-token endpoint token)
          scopes   (set (str/split (str scope) #"[,\s]+"))]
      (cond
        (not info)
        (error-response :unauthorized "Invalid access token.")

        (not= (shared/domain me) (shared/domain (:url conf)))
        (error-response :forbidden "Token was not issued for this site.")

        (and required-scopes (not (some scopes required-scopes)))
        (error-response :insufficient_scope
                        (str "Requires one of these scopes: "
                             (str/join ", " required-scopes)))))
    (error-response :unauthorized "Missing bearer token.")))

;;; Properties

(defn- first-val
  "The first value of `k` in `m`, unwrapping micropub's list values."
  [m k]
  (let [v (get m k)]
    (if (sequential? v) (first v) v)))

(defn- params->post
  "Normalize micropub `params` — form-encoded or JSON syntax — into a partial
  post map of :h, :title, :content, :date, :slug and the response verbs (each
  key absent when not given). The reply verb arrives as `in-reply-to` but is a
  `reply-to` post here; the other three keep their names."
  [params]
  (let [properties (:properties params)
        prop       (fn [k] (or (first-val properties k)
                               (first-val params k)))
        content    (prop :content)]
    (shared/compact
      {:h           (or (some-> (first-val params :type)
                                (str/replace #"^h-" ""))
                        (:h params)
                        "entry")
       :title       (prop :name)
       ;; JSON content can be {"html": ...}; markdown tolerates raw HTML.
       :content     (if (map? content) (:html content) content)
       :date        (prop :published)
       :slug        (prop :mp-slug)
       :reply-to    (prop :in-reply-to)
       :like-of     (prop :like-of)
       :repost-of   (prop :repost-of)
       :bookmark-of (prop :bookmark-of)})))

;;; Creation

(defn- ->frontmatter
  "The YAML frontmatter block of `post`: its date, title, slug and any response
  verb (db/response-verb-attrs), each written out only when present."
  [post]
  (let [lines (for [k     (into [:date :title :slug] db/response-verb-attrs)
                    :let  [v (get post k)]
                    :when v]
                (str (name k) ": " v))]
    (str "---\n" (str/join "\n" lines) "\n---\n\n")))

(defn- derive-slug
  "A URL slug for a new post based on its :slug, :title, :content, or the target
  it responds to."
  [{:keys [slug title content] :as post}]
  (or (not-empty (str slug))
      (some-> title sluj not-empty)
      ;; untitled notes: slugify the first few words of the content
      (some->> (str/split (str content) #"\s+")
               (take 5)
               (str/join " ")
               (sluj)
               (not-empty))
      ;; a response with no words of its own (a like, a bookmark): name it after
      ;; what it responds to.
      (some-> (some post db/response-verb-attrs)
              (str/replace #"^https?://" "")
              (sluj)
              (not-empty))))

(defn- unique-slug
  "Make `slug` unique among the posts of `year` in `conn`."
  [conn year slug]
  (->> (cons slug (map #(str slug "-" %) (iterate inc 2)))
       (remove (partial db/get-post conn year))
       (first)))

(defn create!
  "Create a new post from micropub `params`: writes a markdown file into the
  :posts-dir of `conf` and returns its eventual permalink; the file watcher
  handles the actual db sync. Returns nil for unsupported/invalid params."
  [conn {:keys [posts-dir url] :as conf} params]
  (let [{:keys [h content] :as post} (params->post params)]
    ;; A like/repost/bookmark carries no content of its own, only a verb.
    (when (and (= h "entry")
               (or (not (str/blank? content))
                   (some post db/response-verb-attrs)))
      (when-let [slug (derive-slug post)]
        (let [date (or (some->> (:date post)
                                (re-find #"^\d{4}-\d{2}-\d{2}"))
                       (str (LocalDate/now)))
              year (subs date 0 4)
              slug (unique-slug conn year slug)
              file (io/file posts-dir (str slug ".md"))]
          (spit file (str (->frontmatter (assoc post :date date :slug slug))
                          content "\n"))
          (tel/log! {:level :info
                     :id    ::post-created
                     :data  {:file (str file)}
                     :msg   (str "Micropub post created: " file)})
          (str url (c/post-href year slug)))))))

;;; Endpoint

(defn- url->year+slug
  [url]
  (rest (re-find #"/posts/(\d{4})/([^/]+?)/?$" (str url))))

(defn handle-create
  "Handle a micropub creation POST `req`; the Location header of a successful
  response holds the eventual permalink. 202 rather than 201 since the post
  only goes live once the file watcher has synced it."
  [{:keys [conf conn form-params json-params] :as req}]
  (or (authorize req #{"create" "post"})
      (if-let [location (create! conn conf (or (not-empty json-params)
                                               form-params))]
        {:status  202
         :headers {"Location" location}}
        (error-response :invalid_request
                        "Could not create a post from the request."))))

(defn handle-query
  "Handle a micropub GET query `req`; q=config, q=syndicate-to and q=source
  are supported."
  [{:keys [conf conn query-params] :as req}]
  (or (authorize req nil)
      (let [{:keys [q url]} query-params]
        (case q
          "config"
          (json-response 200 {:syndicate-to []
                              :post-types   [{:type "note" :name "Note"}
                                             {:type "article" :name "Article"}
                                             {:type "reply" :name "Reply"}
                                             {:type "like" :name "Like"}
                                             {:type "repost" :name "Repost"}
                                             {:type "bookmark" :name "Bookmark"}]})

          "syndicate-to"
          (json-response 200 {:syndicate-to []})

          "source"
          (let [[year slug] (url->year+slug url)
                post (db/get-post conn year slug)]
            (if post
              (json-response 200 {:type       ["h-entry"]
                                  :properties (cond-> {:content   [(:content post)]
                                                       :published [(:date post)]}
                                                (:title post)
                                                (assoc :name [(:title post)]))})
              (error-response :invalid_request "No post found at that URL.")))

          (error-response :invalid_request (str "Unsupported query: " q))))))
