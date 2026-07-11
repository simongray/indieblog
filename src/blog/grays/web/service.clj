(ns blog.grays.web.service
  "The core web service; a starting point for reading the source code."
  (:require [io.pedestal.connector :as conn]
            [io.pedestal.http.content-negotiation :as negotiation]
            [io.pedestal.http.jetty :as jetty]
            [io.pedestal.service.resources :as resources]
            [taoensso.telemere :as tel]
            [blog.grays.web.db :as db]
            [blog.grays.web.shared :as shared]
            [blog.grays.web.webmention :as webmention]
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
   :tagline  [:address "My humble place on the web; entirely home-made and up since " [:time {:datetime "2023"} "2023"] "."]
   :identity {"https://github.com/simongray"                     {:label "Github"}
              "https://indieweb.social/@simongray"               {:label "Mastodon"}
              "https://www.linkedin.com/in/simon-gray-54b8a633/" {:label "LinkedIn"}
              "mailto:simon@grays.blog"                          {:label "Email"}}

   ;; IndieWeb (https://indieweb.org/); Webmentions are received natively at
   ;; the /webmention route.
   :webmention-endpoint "https://simon.grays.blog/webmention"
   :indieauth {:authorization-endpoint "https://indieauth.com/auth"
               :token-endpoint         "https://tokens.indieauth.com/token"}
   :micropub-endpoint   "https://simon.grays.blog/micropub"
   :websub-hub          "https://pubsubhubbub.superfeedr.com/"})

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
  [{:keys [development posts-dir port] :as conf}]
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
          #{["/" :get [i/frontpage] :route-name ::frontpage]
            ["/" :head [i/frontpage] :route-name ::frontpage-head]
            ["/posts/:year/:slug" :get [negotiate i/single-post] :route-name ::single-post
             :constraints {:year #"\d\d\d\d"}]
            ["/posts/:year/:slug" :head [negotiate i/single-post] :route-name ::single-post-head
             :constraints {:year #"\d\d\d\d"}]
            [shared/feed-path :get [i/rss-feed] :route-name ::feed]
            [shared/feed-path :head [i/rss-feed] :route-name ::feed-head]
            ;; NB: form params are parsed by the body-params interceptor that
            ;; with-default-interceptors already puts in the global stack.
            ["/webmention" :post [i/webmention] :route-name ::webmention]
            ["/micropub" :post [i/micropub] :route-name ::micropub-create]
            ["/micropub" :get [i/micropub] :route-name ::micropub-query]
            ["/sitemap.xml" :get [i/sitemap] :route-name ::sitemap]
            ["/sitemap.xml" :head [i/sitemap] :route-name ::sitemap-head]}
          (resources/file-routes {:file-root (str posts-dir "/assets")
                                  :prefix    "/assets"})
          (resources/resource-routes {:resource-root "public"
                                      :prefix        "/"})))))

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
