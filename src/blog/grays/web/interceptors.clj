(ns blog.grays.web.interceptors
  "Pedestal interceptors and handlers backing the web service routes."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jsonista.core :as json]
            [io.pedestal.interceptor :as ic]
            [io.pedestal.http.response :as response]
            [taoensso.telemere :as tel]
            [blog.grays.web.feed :as feed]
            [blog.grays.web.component :as c]
            [blog.grays.web.comments :as comments]
            [blog.grays.web.db :as db]
            [blog.grays.web.http :as http]
            [blog.grays.web.shared :as shared]
            [blog.grays.web.signin :as signin]
            [blog.grays.web.webmention :as webmention]
            [blog.grays.web.micropub :as micropub])
  (:import [java.time Instant LocalDate]))

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
              :before-main (c/response-strip (take 3 responses))
              :description (shared/stringify (:tagline conf))
              :path "/"))))

(defn single-post
  "Renders the post at `year`/`slug` as HTML or raw markdown, depending on
  content negotiation or a .md suffix on `slug`; a deleted post answers 410
  Gone, and any other miss is logged and left for the `not-found` interceptor
  to render."
  [{:keys [conf conn path-params accept] :as req}]
  (let [{:keys [year slug]} path-params
        markdown? (or (str/ends-with? slug ".md")
                      (= "text/markdown" (:field accept)))
        slug      (str/replace slug #"\.md$" "")
        path      (c/post-href year slug)]
    (if-let [post (db/get-post conn year slug)]
      (if markdown?
        {:status  200
         :headers {"Content-Type" "text/markdown;charset=utf-8"
                   "Vary"         "Accept"}
         :body    (:content post)}
        (-> (c/page (str (c/post-title post) " — " (:name conf))
                    (c/article post (rand-nth c/palette) conf
                               :mentions (db/get-mentions conn path)
                               :comments (db/get-comments conn path)
                               :reply-context (webmention/reply-context conn conf (:reply-to post)))
                    conf
                    :reader? true
                    :description (c/post-description post)
                    :path path)
            (html-response)
            (assoc-in [:headers "Vary"] "Accept")))
      ;; A permalink that misses in the db but has delivery records is a post
      ;; that once existed: 410 tells both humans and the Webmention deletion
      ;; flow that it is gone on purpose, with no tombstone state beyond the
      ;; bookkeeping already kept for re-sending.
      (if (seq (db/get-delivery-targets conn path))
        (html-response 410 (c/page (str "Gone — " (:name conf))
                                   (c/gone path)
                                   conf))
        (tel/log! {:level :warn
                   :id    ::post-not-found
                   :data  {:year year :slug slug}
                   :msg   (str "No post found for " year "/" slug)})))))

(defn- page-main
  "The main content of the standalone page at `slug`: /about is the site's full
  h-card (c/profile); any other page is plain content (c/plain)."
  [slug page conf]
  (if (= "about" slug)
    (c/profile page conf)
    (c/plain page)))

(defn standalone-page
  "Renders the standalone page named by the request path (one of
  db/page-slugs); a page whose markdown file is absent is left for the
  not-found interceptor to render."
  [{:keys [conf conn uri] :as req}]
  (let [slug (subs uri 1)]
    (when-let [page (db/get-page conn slug)]
      (html-response
        (c/page (str (c/post-title page) " — " (:name conf))
                (page-main slug page conf)
                conf
                :reader? true
                :description (c/post-description page)
                :path uri)))))

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
  means the request was well-formed and targets an existing post. The on-page
  mention form POSTs here too, and only the Accept header tells a person apart
  from a machine: a browser is answered with a redirect back to the post (or a
  styled 400 page) instead of the machine-facing plain text."
  [{:keys [conf conn form-params headers] :as req}]
  (let [{:keys [source target]} form-params
        browser? (str/includes? (get headers "accept" "") "text/html")
        path     (webmention/receive-mention! conn conf source target)]
    (cond
      (and path browser?) {:status  303
                           :headers {"Location" (str path "#comments")}}
      path                (text-response 202 "Accepted")
      browser?            (html-response 400 (c/page (str "Invalid Webmention — " (:name conf))
                                                     (c/invalid-mention source)
                                                     conf))
      :else               (text-response 400 "Invalid Webmention"))))

(def ^:private post-path-re
  ;; The shape of a post permalink: what a visitor-supplied path must match
  ;; before it is trusted anywhere in the sign-in flow.
  #"/posts/(\d{4})/([^/]+)")

(defn- post-at-path
  "The post at the visitor-supplied local `path` in `conn`, provided the path
  has permalink shape; nil otherwise."
  [conn path]
  (when-let [[_ year slug] (some->> path (re-matches post-path-re))]
    (db/get-post conn year slug)))

(defn- sign-in-failure
  [conf]
  (html-response 400 (c/page (str "Sign-in failed — " (:name conf))
                             (c/sign-in-failed)
                             conf)))

(defn sign-in
  "Begins a visitor's Web sign-in: their claimed URL and the post they came
  from are validated, then they are sent off to the sign-in endpoint of conf
  carrying a signed state (see the signin namespace)."
  [{:keys [conf conn form-params] :as req}]
  (let [{:keys [me path]} form-params]
    (if (and (:sign-in conf)
             (http/valid-url? me)
             (post-at-path conn path))
      {:status  303
       :headers {"Location" (signin/auth-url conf me (signin/token {:path path}))}}
      (sign-in-failure conf))))

(defn sign-in-callback
  "Completes a visitor's Web sign-in: our state is verified, the code is
  exchanged for their authenticated site, and they get the comment form."
  [{:keys [conf conn query-params] :as req}]
  (let [{:keys [code state]} query-params
        {:keys [path]} (signin/read-token state signin/state-max-age)
        post           (post-at-path conn path)
        me             (when (and post code (:sign-in conf))
                         (signin/exchange-code! conf code))]
    (if me
      (html-response (c/page (str "Write a comment — " (:name conf))
                             (c/comment-form post path me (signin/token {:me me :path path}))
                             conf))
      (sign-in-failure conf))))

(defn post-comment
  "Accepts a signed-in visitor's comment. The signed token proves who they are
  and which post they came from; their homepage's h-card fills in the name and
  photo their comment is displayed with. The watcher syncs the written file
  into the db, so the comment appears shortly after the redirect."
  [{:keys [conf form-params] :as req}]
  (let [{:keys [token content]} form-params
        {:keys [me path]} (signin/read-token token signin/comment-max-age)
        content (some-> content str/trim not-empty)]
    (if (and me content (<= (count content) shared/comment-max-length))
      (do (comments/put-comment!
            (:comments-dir conf) path
            (shared/compact
              (merge {:status    :approved
                      :auth      :indieauth
                      :received  (str (Instant/now))
                      :published (str (LocalDate/now))
                      :content   content}
                     (webmention/author-attrs conf me))))
          {:status  303
           :headers {"Location" (str path "#comments")}})
      (sign-in-failure conf))))

(defn micropub
  "The Micropub endpoint: entry create/update/delete via POST, queries via GET;
  auth is handled inside via the delegated IndieAuth token endpoint."
  [{:keys [request-method] :as req}]
  (case request-method
    :post (micropub/handle-post req)
    :get  (micropub/handle-query req)))

(defn media
  "The Micropub media endpoint: stores an uploaded file and returns its URL.
  Multipart parsing is a route-scoped interceptor (see service)."
  [req]
  (micropub/handle-media req))

(defn sitemap
  [{:keys [conf conn] :as req}]
  {:status  200
   :headers {"Content-Type" "application/xml"}
   :body    (feed/sitemap-xml conf (db/get-posts conn)
                              :pages (db/get-pages conn))})

(defn sitemap-xsl
  "Serves the sitemap stylesheet with the XSLT content type; the generic
  resource route falls back to octet-stream for .xsl, which browsers refuse
  to apply."
  [_]
  {:status  200
   :headers {"Content-Type" "application/xslt+xml"}
   :body    (slurp (io/resource "public/sitemap.xsl"))})

(defn bridgy-fed-redirect
  "Redirects /.well-known/webfinger and /.well-known/host-meta to Bridgy Fed,
  query string included (the ?resource=acct:… query is the whole request);
  this is what upgrades the fediverse handle from @domain@web.brid.gy to
  @domain@domain. NB: it must stay a redirect, since Bridgy Fed refuses a
  site that serves WebFinger itself."
  [{:keys [conf uri query-string] :as req}]
  {:status  302
   ;; :bridgy-fed ends in a slash and uri starts with one; doubling the slash
   ;; here would 404 on their end.
   :headers {"Location" (str (:bridgy-fed conf) (subs uri 1)
                             (when query-string (str "?" query-string)))}})

(defn api-catalog
  "The RFC 9727 catalog of the machine endpoints this host exposes, as an
  RFC 9264 linkset; each anchor is the endpoint and its service-doc the spec
  that documents how to speak to it."
  [{:keys [conf] :as req}]
  (let [{:keys [url webmention-endpoint micropub-endpoint media-endpoint]} conf
        entry (fn [anchor doc-href]
                {:anchor      anchor
                 :service-doc [{:href doc-href :type "text/html"}]})]
    {:status  200
     :headers {"Content-Type" "application/linkset+json"}
     :body    (json/write-value-as-string
                {:linkset [(entry webmention-endpoint "https://www.w3.org/TR/webmention/")
                           (entry micropub-endpoint "https://micropub.spec.indieweb.org/")
                           (entry media-endpoint "https://micropub.spec.indieweb.org/#media-endpoint")
                           (entry (str url shared/feed-path) "https://www.rssboard.org/rss-specification")]})}))

(defn security-txt
  "The RFC 9116 security contact. Generated from conf rather than served as a
  static file so the contact cannot go stale; Expires (a required field) rolls
  half a year ahead for the same reason."
  [{:keys [conf uri] :as req}]
  (let [expires (-> (java.time.Instant/now)
                    (.plus (java.time.Duration/ofDays 182))
                    (.truncatedTo java.time.temporal.ChronoUnit/SECONDS))]
    {:status  200
     :headers {"Content-Type" "text/plain;charset=utf-8"}
     :body    (str "Contact: mailto:" (:email conf) "\n"
                   "Expires: " expires "\n"
                   "Preferred-Languages: " (:language conf) "\n"
                   "Canonical: " (:url conf) uri "\n")}))
