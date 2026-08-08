(ns blog.grays.web.service
  "The core web service; a starting point for reading the source code."
  (:require [io.pedestal.connector :as conn]
            [io.pedestal.http.content-negotiation :as negotiation]
            [io.pedestal.http.jetty :as jetty]
            [io.pedestal.http.ring-middlewares :as middlewares]
            [io.pedestal.service.resources :as resources]
            [taoensso.telemere :as tel]
            [blog.grays.web.db :as db]
            [blog.grays.web.shared :as shared]
            [blog.grays.web.indieweb.webmention :as webmention]
            [blog.grays.web.interceptors :as i])
  (:gen-class))

(defonce server
  (atom nil))

(def conf
  {:url      "https://simon.grays.blog"
   :name     "Simon Gray's blog"
   :port     4567
   :language "en"
   :email    "simon@grays.blog"
   :author   "Simon Gray"
   ;; The representative h-card's photo. Bridgy Fed refuses to bridge a profile
   ;; without one, as a spam filter. Kept small on purpose: the h-card hides it,
   ;; but a hidden <img> is fetched all the same, so every reader pays for it.
   :photo    "/images/profile-picture-small.jpg"
   ;; The /about page's visible portrait (component/profile); :photo above
   ;; stays the small hidden one every page carries.
   :portrait "/images/profile-picture.jpg"
   ;; The h-card's p-locality/p-country-name, shown on /about.
   :locality "Copenhagen"
   :country  "Denmark"
   :tagline  [:address "My home on the web since " [:time {:datetime "2023"} "2023"] " and an occasional outlet for my thoughts."]
   :identity {"https://github.com/simongray"                     {:label "Github"}
              "https://indieweb.social/@simongray"               {:label "Mastodon"}
              "https://www.linkedin.com/in/simon-gray-54b8a633/" {:label "LinkedIn"}
              "mailto:simon@grays.blog"                          {:label "Email"}}

   ;; IndieWeb (https://indieweb.org/); Webmentions are received natively at
   ;; the /webmention route.
   :webmention-endpoint "https://simon.grays.blog/webmention"

   ;; TODO: self-host IndieAuth; see doc/indieweb.md §8a. Delegation has run out
   ;; of road. indieauth.com is deprecated with no successor, and the rel values
   ;; below are deprecated too, in favour of rel=indieauth-metadata → a metadata
   ;; document that only the authorization server itself can serve. At one user
   ;; an auth+token endpoint is small, and verify-token then stops calling a
   ;; stranger over HTTP on every Micropub request.
   ;; https://indieauth.spec.indieweb.org/#discovery and https://indieauth.com/
   :indieauth {:authorization-endpoint "https://indieauth.com/auth"
               :token-endpoint         "https://tokens.indieauth.com/token"}
   ;; Web sign-in for native comments (see the signin namespace); remove the
   ;; key and the whole comment flow turns off.
   :sign-in  {:endpoint "https://indielogin.com/auth"}
   :micropub-endpoint   "https://simon.grays.blog/micropub"
   :media-endpoint      "https://simon.grays.blog/media"
   :websub-hub          "https://pubsubhubbub.superfeedr.com/"

   ;; Bridgy Fed (https://fed.brid.gy/) federates the blog into the fediverse
   ;; and Bluesky: people there follow this domain itself rather than a copy of
   ;; it. A post links here and sends a Webmention, and Bridgy Fed does the rest.
   ;; This is not POSSE; nothing is syndicated and no silo account exists.
   ;; NB: the bridge must also be enabled once, by hand; see doc/indieweb.md.
   :bridgy-fed          "https://fed.brid.gy/"})

(def prod-conf
  (assoc conf
    :db-dir "/opt/blog/simon.grays.blog/db/"
    :posts-dir "/opt/blog/simon.grays.blog/posts/"
    :indieweb-dir "/opt/blog/simon.grays.blog/indieweb/"
    ;; Automatically send Webmentions and ping the WebSub hub when the
    ;; watcher syncs a post; only meaningful where source URLs are public.
    :send-webmentions? true))

(def dev-conf
  (assoc conf
    :development true
    :db-dir "/Users/simongray/Code/simon.grays.blog/db/"
    :posts-dir "/Users/simongray/Code/simon.grays.blog/posts/"
    :indieweb-dir "/Users/simongray/Code/simon.grays.blog/indieweb/"))

(defn ->connector-map
  [{:keys [development posts-dir indieweb-dir port] :as conf}]
  (let [csp       (if development
                    {:default-src "'self' 'unsafe-inline' 'unsafe-eval' localhost:* 0.0.0.0:* ws://localhost:* ws://0.0.0.0:*"}
                    {:default-src     "'self'"
                     :style-src       "'self' 'unsafe-inline'"
                     :base-uri        "'self'"
                     :frame-ancestors "'none'"})
        ;; Posts are also served as raw markdown; HTML comes first, making it
        ;; the preferred representation e.g. for wildcard Accept headers.
        negotiate (negotiation/negotiate-content ["text/html" "text/markdown"])]
    (-> (conn/default-connector-map "0.0.0.0" port)
        ;; CSP and (dev-only) permissive CORS are configured here.
        (conn/with-default-interceptors
          :secure-headers {:content-security-policy-settings csp}
          ;; Make sure we can communicate with the Shadow CLJS app during dev.
          :allowed-origins (when development (constantly true)))
        ;; Attach conf and the content db connection to every request before
        ;; routing/handlers run.
        (conn/with-interceptor (i/attach-conf conf))
        ;; Attach Cache-Control headers to every response and replace
        ;; Pedestal's plain-text 404 with the styled page; as :leave fns they
        ;; run in reverse order, so cache-control must precede not-found in
        ;; order to also see its 404 responses.
        (conn/with-interceptor i/cache-control)
        (conn/with-interceptor i/not-found)
        ;; Posts live under "/posts/" so their two-segment permalinks don't
        ;; collide with root-level resource paths like "/css/main.css".
        (conn/with-routes
          ;; TODO: add a route (+ UI) for db/search-posts full-text search
          (into
            #{["/" :get [i/frontpage] :route-name ::frontpage]
              ["/" :head [i/frontpage] :route-name ::frontpage-head]
              ["/posts/:year/:slug" :get [negotiate i/single-post] :route-name ::single-post
               :constraints {:year #"\d\d\d\d"}]
              ["/posts/:year/:slug" :head [negotiate i/single-post] :route-name ::single-post-head
               :constraints {:year #"\d\d\d\d"}]
              [shared/feed-path :get [i/rss-feed] :route-name ::feed]
              [shared/feed-path :head [i/rss-feed] :route-name ::feed-head]
              [shared/tags-path :get [i/tag-index] :route-name ::tag-index]
              [shared/tags-path :head [i/tag-index] :route-name ::tag-index-head]
              ["/tags/:tag" :get [i/tagged] :route-name ::tagged]
              ["/tags/:tag" :head [i/tagged] :route-name ::tagged-head]
              ["/tags/:tag/feed" :get [i/tag-feed] :route-name ::tag-feed]
              ["/tags/:tag/feed" :head [i/tag-feed] :route-name ::tag-feed-head]
              ;; NB: form params are parsed by the body-params interceptor that
              ;; with-default-interceptors already puts in the global stack.
              ["/webmention" :post [i/webmention] :route-name ::webmention]
              ;; Web sign-in and native comments; the flow runs across all
              ;; three (see the signin namespace and interceptors).
              ["/sign-in" :post [i/sign-in] :route-name ::sign-in]
              ["/sign-in/callback" :get [i/sign-in-callback] :route-name ::sign-in-callback]
              ["/comments" :post [i/post-comment] :route-name ::post-comment]
              ["/micropub" :post [i/micropub] :route-name ::micropub-create]
              ["/micropub" :get [i/micropub] :route-name ::micropub-query]
              ;; The Micropub media endpoint; its multipart parsing is scoped to
              ;; this route rather than the global stack. Uploads land in the
              ;; posts assets/ dir, already served below.
              ["/media" :post [(middlewares/multipart-params) i/media] :route-name ::micropub-media]
              ["/sitemap.xml" :get [i/sitemap] :route-name ::sitemap]
              ["/sitemap.xml" :head [i/sitemap] :route-name ::sitemap-head]
              ;; The stylesheet needs its own route only for the content type;
              ;; the resource fallback serves .xsl as octet-stream.
              ["/sitemap.xsl" :get [i/sitemap-xsl] :route-name ::sitemap-xsl]
              ["/sitemap.xsl" :head [i/sitemap-xsl] :route-name ::sitemap-xsl-head]
              ;; Well-known URIs (RFC 8615). The WebFinger/host-meta redirects
              ;; give the site its @domain@domain fediverse handle; see
              ;; doc/indieweb.md §10a.
              ["/.well-known/webfinger" :get [i/bridgy-fed-redirect] :route-name ::webfinger]
              ["/.well-known/host-meta" :get [i/bridgy-fed-redirect] :route-name ::host-meta]
              ["/.well-known/api-catalog" :get [i/api-catalog] :route-name ::api-catalog]
              ["/.well-known/api-catalog" :head [i/api-catalog] :route-name ::api-catalog-head]
              ["/.well-known/security.txt" :get [i/security-txt] :route-name ::security-txt]
              ["/.well-known/security.txt" :head [i/security-txt] :route-name ::security-txt-head]}
            ;; The standalone pages (/about, /now): a GET+HEAD route per
            ;; db/page-slugs entry, each backed by a markdown file of the same
            ;; name in the posts dir.
            (mapcat (fn [slug]
                      [[(str "/" slug) :get [i/standalone-page]
                        :route-name (keyword "blog.grays.web.service" slug)]
                       [(str "/" slug) :head [i/standalone-page]
                        :route-name (keyword "blog.grays.web.service" (str slug "-head"))]]))
            db/page-slugs)
          (resources/file-routes {:file-root (str posts-dir "/" shared/assets-dir)
                                  :prefix    shared/assets-path
                                  ;; No response caching: Pedestal freezes each
                                  ;; file's Content-Length at first request, but
                                  ;; assets change under a running server (the
                                  ;; posts watcher syncs them), and a changed
                                  ;; length means truncated responses.
                                  :cache?    false})
          ;; Cached avatars of the people who mention us (see component/face).
          ;; Rooted at exactly the avatars subdir, so the rest of the indieweb
          ;; dir (mentions, deliveries, moderation) stays unreachable; served
          ;; from our own origin, which is what keeps CSP at default-src 'self'.
          (resources/file-routes {:file-root (str indieweb-dir "/avatars")
                                  :prefix    "/avatars"
                                  ;; As above: cache-avatar! rewrites files
                                  ;; under a running server.
                                  :cache?    false
                                  ;; A namespace of its own for the generated
                                  ;; route names: file-routes derives them from
                                  ;; this plus a fixed suffix, not the prefix, so
                                  ;; two default-namespaced sets would collide.
                                  :route-namespace "avatars"})
          (resources/resource-routes {:resource-root "public"
                                      :prefix        "/"
                                      ;; The frozen Content-Length (see the file
                                      ;; routes above) is harmless in prod, where
                                      ;; the classpath is an immutable jar, but
                                      ;; in dev the resources dir is edited live.
                                      :cache?        (not development)})))))

(defn start!
  "Start the blog server, with `overrides` merged onto prod-conf; blocks unless
  the resulting conf is in :development.

  The 1-arity makes the fn compatible with `clojure -X:server`, where any
  supplied kvs (e.g. :port) override the production defaults."
  ([]
   (start! {}))
  ([overrides]
   (let [{:keys [development port db-dir send-webmentions?] :as conf} (merge prod-conf overrides)]
     (db/start! conf :on-sync (when send-webmentions?
                                (partial webmention/schedule-notify!
                                         (db/get-conn db-dir) conf)))
     (tel/log! {:level :info
                :id    ::server-start
                :data  {:env (if development :dev :prod) :port port}
                :msg   (str "Starting blog server on port " port)})
     (let [connector (-> (cond-> (->connector-map conf)
                           (not development) (assoc :join? true))
                         (jetty/create-connector nil))]
       (reset! server connector)
       (conn/start! connector)))))

(defn stop!
  []
  (some-> @server conn/stop!))

(defn restart!
  "(Re)start the development server; a REPL convenience."
  []
  (stop!)
  (start! dev-conf))

(defn -main
  [& args]
  (start!))

(comment
  (restart!)
  (stop!)
  #_.)
