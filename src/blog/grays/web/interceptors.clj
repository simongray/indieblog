(ns blog.grays.web.interceptors
  "Pedestal interceptors and handlers backing the web service routes."
  (:require [clojure.string :as str]
            [io.pedestal.interceptor :as ic]
            [io.pedestal.http.response :as response]
            [taoensso.telemere :as tel]
            [blog.grays.web.feed :as feed]
            [blog.grays.web.component :as c]
            [blog.grays.web.db :as db]
            [blog.grays.web.shared :as shared]
            [blog.grays.web.webmention :as webmention]
            [blog.grays.web.micropub :as micropub]))

(defn attach-conf
  "Attaches `conf` and the content db connection to the request; should
  precede handlers."
  [{:keys [db-dir] :as conf}]
  (let [conn (db/get-conn db-dir)]
    (ic/interceptor
      {:name  ::attach-conf
       :enter (fn [ctx] (update ctx :request assoc :conf conf :conn conn))})))

(defn html-response
  ([body]
   (html-response 200 body))
  ([status body]
   {:status  status
    :headers {"Content-Type" "text/html;charset=utf-8"}
    :body    body}))

(defn text-response
  [status body]
  {:status  status
   :headers {"Content-Type" "text/plain"}
   :body    body})

(def not-found
  "Renders the styled 404 page whenever no handler produced a response, e.g.
  for unrouted paths or missing posts; Pedestal's own not-found interceptor
  would otherwise return plain text. Must come after `attach-conf` so that
  :conf is present on the request."
  (ic/interceptor
    {:name  ::not-found
     :leave (fn [{:keys [request] :as ctx}]
              (if (and (not (response/response? (:response ctx)))
                       (response/response-expected? ctx))
                (let [{:keys [conf uri]} request]
                  (assoc ctx :response
                         (html-response 404 (c/page (str "Not found — " (:name conf))
                                                    (c/not-found uri)
                                                    conf))))
                ctx))}))

(def ^:private dynamic-content-types
  "Content-type prefixes of dynamically generated responses; never cached."
  #{"text/html" "text/markdown" "application/rss+xml" "application/xml"})

(defn- cache-control-value
  "The Cache-Control header value for a `request`/`response` pair: dynamic
  content is revalidated on every request, while static files are cached; the
  stylesheet gets a year since /css/main.css is referenced with a ?v= param
  which *must* be bumped whenever the file changes."
  [{:keys [uri]} {:keys [status headers]}]
  (let [content-type (get headers "Content-Type" "")]
    (cond
      (or (not= 200 status)
          (some #(str/starts-with? content-type %) dynamic-content-types))
      "no-cache"

      (str/starts-with? uri "/css/")
      "public, max-age=31536000, immutable"

      :else
      "public, max-age=604800")))

(def cache-control
  "Attaches a Cache-Control header to any response that doesn't set its own."
  (ic/interceptor
    {:name  ::cache-control
     :leave (fn [{:keys [request response] :as ctx}]
              (if (and response (nil? (get-in response [:headers "Cache-Control"])))
                (assoc-in ctx [:response :headers "Cache-Control"]
                          (cache-control-value request response))
                ctx))}))

(defn frontpage
  [{:keys [conf conn] :as req}]
  (let [{articles false responses true} (group-by db/response-post?
                                                  (db/get-posts conn))]
    (html-response
      (c/page (:name conf)
              (c/articles articles conf)
              conf
              :frontpage? true
              :before-main (c/responses (take 3 responses))
              :description (shared/stringify (:tagline conf))
              :path "/"))))

(defn single-post
  "Renders the post at `year`/`slug` as HTML or raw markdown, depending on
  content negotiation or a .md suffix on `slug`; a miss is logged and left
  for the `not-found` interceptor to render."
  [{:keys [conf conn path-params accept] :as req}]
  (let [{:keys [year slug]} path-params
        markdown? (or (str/ends-with? slug ".md")
                      (= "text/markdown" (:field accept)))
        slug      (str/replace slug #"\.md$" "")]
    (if-let [post (db/get-post conn year slug)]
      (if markdown?
        {:status  200
         :headers {"Content-Type" "text/markdown;charset=utf-8"
                   "Vary"         "Accept"}
         :body    (:content post)}
        (-> (c/page (str (c/post-title post) " — " (:name conf))
                    (c/article post (rand-nth c/palette) conf
                               :mentions (db/get-mentions conn (c/post-href year slug))
                               :reply-context (webmention/reply-context conn conf (:reply-to post)))
                    conf
                    :reader? true
                    :description (c/post-description post)
                    :path (c/post-href year slug))
            (html-response)
            (assoc-in [:headers "Vary"] "Accept")))
      (tel/log! {:level :warn
                 :id    ::post-not-found
                 :data  {:year year :slug slug}
                 :msg   (str "No post found for " year "/" slug)}))))

(defn rss-feed
  "Renders the RSS feed; the Link header advertises the WebSub hub and the
  canonical (self) feed URL for hub discovery."
  [{:keys [conf conn] :as req}]
  (let [{:keys [url websub-hub]} conf]
    {:status  200
     :headers (cond-> {"Content-Type" "application/rss+xml"}
                websub-hub
                (assoc "Link" (str "<" websub-hub ">; rel=\"hub\", "
                                   "<" url shared/feed-path ">; rel=\"self\"")))
     :body    (feed/xml conf (->> (db/get-posts conn)
                                  (remove db/response-post?)
                                  (take 10)))}))

(defn tag-index
  "Renders the h-feed of posts tagged with the `tag` path-param; an unused tag
  matches nothing and is left for the not-found interceptor to render."
  [{:keys [conf conn path-params] :as req}]
  (let [{:keys [tag]} path-params]
    (when-let [posts (seq (db/get-posts-by-tag conn tag))]
      (html-response
        (c/page (str "#" tag " — " (:name conf))
                (c/tagged tag posts conf)
                conf
                :h-feed? true
                :description (str "Posts tagged #" tag)
                :path (str "/tags/" tag))))))

(defn tag-feed
  "Renders the RSS feed of articles tagged with the `tag` path-param; an unused
  tag matches nothing and is left for the not-found interceptor.

  No WebSub Link header: the hub is pinged only for the main feed, so a per-tag
  feed must not advertise a hub that will never notify it."
  [{:keys [conf conn path-params] :as req}]
  (let [{:keys [tag]}   path-params
        tagged          (db/get-posts-by-tag conn tag)]
    (when (seq tagged)
      {:status  200
       :headers {"Content-Type" "application/rss+xml"}
       :body    (feed/xml conf (->> tagged (remove db/response-post?) (take 10))
                          :title       (str (:name conf) ": #" tag)
                          :description (str "Posts tagged #" tag)
                          :feed-url    (str (:url conf) "/tags/" tag "/feed"))})))

(defn webmention
  "Accepts incoming Webmentions; verification is asynchronous, so a 202 only
  means the request was well-formed and targets an existing post."
  [{:keys [conf conn form-params] :as req}]
  (let [{:keys [source target]} form-params]
    (if (webmention/receive-mention! conn conf source target)
      (text-response 202 "Accepted")
      (text-response 400 "Invalid Webmention"))))

(defn micropub
  "The Micropub endpoint: entry create/update/delete via POST, queries via GET;
  auth is handled inside via the delegated IndieAuth token endpoint."
  [{:keys [request-method] :as req}]
  (case request-method
    :post (micropub/handle-post req)
    :get  (micropub/handle-query req)))

(defn sitemap
  [{:keys [conf conn] :as req}]
  {:status  200
   :headers {"Content-Type" "application/xml"}
   :body    (feed/sitemap-xml conf (db/get-posts conn))})
