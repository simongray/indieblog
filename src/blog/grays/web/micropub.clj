(ns blog.grays.web.micropub
  "A minimal Micropub endpoint (https://micropub.spec.indieweb.org/).

  Create, update and delete are supported, each reducing to a file operation on
  the :posts-dir: create and update write a markdown file, delete removes one.
  The watcher then syncs the change into the content db like any hand-written
  edit, including any Webmention/WebSub notifications; a deletion re-sends its
  Webmentions to withdraw the federated copies. Undelete is not supported.
  Authentication is delegated: bearer tokens are verified against the :indieauth
  token endpoint and must have been issued for the blog's own domain."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [jsonista.core :as json]
            [sluj.core :refer [sluj]]
            [taoensso.telemere :as tel]
            [blog.grays.web.content :as content]
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

(defn- content-value
  "The markdown for an mf2 `content` value: its :html when a map (raw HTML that
  markdown tolerates), else the value itself."
  [content]
  (if (map? content) (:html content) content))

(defn- params->post
  "Normalize micropub `params` — form-encoded or JSON syntax — into a partial
  post map of :h, :title, :content, :date, :slug, :tags and the response verbs
  (each key absent when not given). The reply verb arrives as `in-reply-to` but
  is a `reply-to` post here; the other three keep their names."
  [params]
  (let [properties (:properties params)
        prop       (fn [k] (or (first-val properties k)
                               (first-val params k)))
        ;; A form-encoded array property arrives under <prop>[] (Micropub's
        ;; convention); JSON puts its values under the plain key in :properties.
        prop-vals  (fn [k] (let [v (or (get properties k)
                                       (get params k)
                                       (get params (keyword (str (name k) "[]"))))]
                             (cond (sequential? v) v
                                   (some? v)       [v])))
        content    (prop :content)]
    (shared/compact
      {:h           (or (some-> (first-val params :type)
                                (str/replace #"^h-" ""))
                        (:h params)
                        "entry")
       :title       (prop :name)
       :content     (content-value content)
       :date        (prop :published)
       :slug        (prop :mp-slug)
       ;; category is multi-valued; store it as the comma-separated tags string
       ;; a hand-written post uses (content/parse-tags splits it back out).
       :tags        (some->> (prop-vals :category)
                             (remove str/blank?)
                             (seq)
                             (str/join ", "))
       :reply-to    (prop :in-reply-to)
       :like-of     (prop :like-of)
       :repost-of   (prop :repost-of)
       :bookmark-of (prop :bookmark-of)})))

;;; Creation

(defn- frontmatter-block
  "The YAML frontmatter block for the `[key value]` pairs `kvs`, one `key: value`
  line each; a nil value is skipped and its key omitted."
  [kvs]
  (str "---\n"
       (str/join "\n" (for [[k v] kvs :when (some? v)]
                        (str (name k) ": " v)))
       "\n---\n\n"))

(defn- ->frontmatter
  "The YAML frontmatter block of `post`: its date, title, slug, tags and any
  response verb (db/response-verb-attrs), each written out only when present."
  [post]
  (frontmatter-block (for [k (into [:date :title :slug :tags] db/response-verb-attrs)]
                       [k (get post k)])))

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

;;; Updating

(def ^:private update-property->attr
  "Micropub mf2 properties an update may change, mapped to the post attribute
  each affects. :published and :mp-slug are deliberately absent: they fix the
  filename and permalink, so an update leaves them alone (see handle-update)."
  {:name        :title
   :content     :content
   :category    :tags
   :in-reply-to :reply-to
   :like-of     :like-of
   :repost-of   :repost-of
   :bookmark-of :bookmark-of
   :syndication :syndication})

(def ^:private list-attrs
  "Frontmatter attributes holding several values: how to split the file form
  and how to rejoin it. An attribute absent here is a scalar."
  {:tags        {:split #",\s*" :join ", "}
   :syndication {:split #"\s+"  :join " "}})

(defn- split-values
  "The values of `attr` in `frontmatter` as a vector, or nil when unset."
  [frontmatter attr]
  (when-let [v (get frontmatter attr)]
    (if-let [{:keys [split]} (list-attrs attr)]
      (vec (str/split v split))
      [v])))

(defn- put-values
  "Set `attr` in `frontmatter` to `values`, joining a list attribute and taking
  the first of a scalar; an empty result dissocs the attribute entirely."
  [frontmatter attr values]
  (let [values (remove str/blank? values)]
    (if (empty? values)
      (dissoc frontmatter attr)
      (assoc frontmatter attr
             (if-let [{:keys [join]} (list-attrs attr)]
               (str/join join values)
               (first values))))))

(defn- edit-attr
  "Apply update `op` (:replace, :add or :delete) with `values` to `attr` in
  `state`, a {:frontmatter :body} map. :content is the body; the rest are
  frontmatter fields. A :delete with nil `values` drops the whole attribute."
  [state op attr values]
  (if (= attr :content)
    (assoc state :body (case op
                         :delete ""
                         (content-value (first values))))
    (update state :frontmatter
            (fn [fm]
              (let [current (split-values fm attr)]
                (case op
                  :replace (put-values fm attr values)
                  :add     (put-values fm attr (concat current values))
                  :delete  (if values
                             (put-values fm attr (remove (set values) current))
                             (dissoc fm attr))))))))

(defn- apply-update
  "Apply a micropub update `body` (its :replace, :add and :delete ops) to a
  post's `[frontmatter body]` pair, returning the updated pair. :delete may name
  whole properties (a vector) or specific values to remove (a map)."
  [[frontmatter body] {:keys [replace add delete]}]
  (let [deletes (if (map? delete)
                  delete
                  (zipmap (map keyword delete) (repeat nil)))
        ops     (concat (for [[p vs] replace] [:replace p vs])
                        (for [[p vs] add]     [:add p vs])
                        (for [[p vs] deletes] [:delete p vs]))]
    (-> (reduce (fn [state [op prop values]]
                  (if-let [attr (update-property->attr prop)]
                    (edit-attr state op attr values)
                    state))
                {:frontmatter frontmatter :body body}
                ops)
        ((juxt :frontmatter :body)))))

(defn- parse-file
  "The `[frontmatter body]` of the markdown `file`, parsed as content/md->post
  does so the in-memory view matches the db's."
  [file]
  (let [text (slurp file)
        [match yaml] (re-find content/yaml-frontmatter text)
        body (if match (str/trim (subs text (count match))) text)]
    [(if yaml (content/yaml->map yaml) {}) body]))

(defn- write-post!
  "Write the `[frontmatter body]` pair back to `file` as markdown."
  [file [frontmatter body]]
  (spit file (str (frontmatter-block frontmatter) body "\n")))

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

(defn- handle-update
  "Handle a micropub update POST `req`: applies its :replace/:add/:delete ops to
  the post at :url and rewrites the file, which the watcher then re-syncs. 204 on
  success; the permalink never changes, so no Location is returned."
  [{:keys [conn json-params] :as req}]
  (or (authorize req #{"update"})
      (let [[year slug] (url->year+slug (:url json-params))]
        (if-let [file (:file (db/get-post conn year slug))]
          (do
            (write-post! file (apply-update (parse-file file) json-params))
            (tel/log! {:level :info
                       :id    ::post-updated
                       :data  {:file file}
                       :msg   (str "Micropub post updated: " file)})
            {:status 204})
          (error-response :invalid_request "No post found at that URL.")))))

(defn- handle-delete
  "Handle a micropub delete POST `req`: removes the file of the post at :url, so
  the watcher retracts it and re-sends its Webmentions to withdraw any federated
  copies. 204 on success. This is a hard delete; undelete is not supported."
  [{:keys [conn json-params form-params] :as req}]
  (or (authorize req #{"delete"})
      (let [[year slug] (url->year+slug (:url (or (not-empty json-params)
                                                  form-params)))]
        (if-let [file (:file (db/get-post conn year slug))]
          (do
            (io/delete-file file)
            (tel/log! {:level :info
                       :id    ::post-deleted
                       :data  {:file file}
                       :msg   (str "Micropub post deleted: " file)})
            {:status 204})
          (error-response :invalid_request "No post found at that URL.")))))

(def ^:private content-type->ext
  "Image content types the media endpoint accepts, mapped to the extension to
  store them under; the fallback when an upload's filename carries no usable
  extension. Values are content/img-ext members."
  {"image/jpeg"    "jpg"
   "image/png"     "png"
   "image/gif"     "gif"
   "image/svg+xml" "svg"})

(defn handle-media
  "Handle a Micropub media-endpoint upload `req`: stores the multipart `file`
  part under the posts assets/ dir and returns 201 with its served URL in
  Location. 201 rather than 202 (unlike handle-create): the file is served
  statically, so it is live at once, with no watcher sync to wait on."
  [{:keys [conf multipart-params] :as req}]
  (or (authorize req #{"media" "create"})
      (let [{:keys [posts-dir url]} conf
            {:keys [filename content-type tempfile]} (get multipart-params "file")
            ;; Prefer the filename's own extension; fall back to the declared
            ;; content type so a filename-less upload of a known image type
            ;; still stores correctly.
            ext (or (some-> filename content/file-ext str/lower-case content/img-ext)
                    (some-> content-type str/lower-case content-type->ext))]
        (if (and tempfile ext)
          (let [dir   (io/file posts-dir "assets")
                base  (str (LocalDate/now) "-" (or (content/file-slug filename) "photo"))
                ;; Unique within assets/ by numbering the basename, as
                ;; unique-slug does for posts within a year.
                fname (->> (cons base (map #(str base "-" %) (iterate inc 2)))
                           (map #(str % "." ext))
                           (remove #(.exists (io/file dir %)))
                           (first))
                dest  (io/file dir fname)]
            (io/make-parents dest)
            (io/copy tempfile dest)
            (tel/log! {:level :info
                       :id    ::media-uploaded
                       :data  {:file (str dest)}
                       :msg   (str "Micropub media uploaded: " dest)})
            {:status  201
             :headers {"Location" (str url "/assets/" fname)}})
          (error-response :invalid_request
                          "Expected an image file in the \"file\" part.")))))

(defn handle-post
  "Handle a micropub POST `req`, dispatching on its action: update, delete, or
  create (the default). See handle-create/handle-update/handle-delete."
  [{:keys [json-params form-params] :as req}]
  (let [action (or (:action json-params) (:action form-params))]
    (case action
      "update"       (handle-update req)
      "delete"       (handle-delete req)
      (nil "create") (handle-create req)
      (error-response :invalid_request (str "Unsupported action: " action)))))

(defn handle-query
  "Handle a micropub GET query `req`; q=config, q=syndicate-to and q=source
  are supported."
  [{:keys [conf conn query-params] :as req}]
  (or (authorize req nil)
      (let [{:keys [q url]} query-params]
        (case q
          "config"
          (json-response 200 {:media-endpoint (:media-endpoint conf)
                              :syndicate-to   []
                              :post-types     [{:type "note" :name "Note"}
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
