(ns blog.grays.web.interceptors
  "Pedestal interceptors and handlers backing the web service routes."
  (:require [clojure.string :as str]
            [io.pedestal.interceptor :as ic]
            [io.pedestal.http.response :as response]
            [taoensso.telemere :as tel]
            [blog.grays.web.feed :as feed]
            [blog.grays.web.component :as c]
            [blog.grays.web.db :as db]
            [blog.grays.web.shared :as shared]))

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
  (html-response
    (c/page (:name conf)
            (c/articles (db/get-posts conn))
            conf
            :frontpage? true
            :description (shared/stringify (:tagline conf))
            :path "/")))

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
        (-> (c/page (str (:title post) " — " (:name conf))
                    (c/article post (rand-nth c/palette))
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
  [{:keys [conf conn] :as req}]
  {:status  200
   :headers {"Content-Type" "application/rss+xml"}
   :body    (feed/xml conf (take 10 (db/get-posts conn)))})

(defn sitemap
  [{:keys [conf conn] :as req}]
  {:status  200
   :headers {"Content-Type" "application/xml"}
   :body    (feed/sitemap-xml conf (db/get-posts conn))})
