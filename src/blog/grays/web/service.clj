(ns blog.grays.web.service
  "The core web service; a starting point for reading the source code."
  (:require [io.pedestal.connector :as conn]
            [io.pedestal.http.jetty :as jetty]
            [io.pedestal.service.resources :as resources]
            [blog.grays.web.db :as db]
            [blog.grays.web.shared :as shared]
            [blog.grays.web.interceptors :as i])
  (:gen-class))

(defonce server
  (atom nil))

(def conf
  {:url      "https://simon.grays.blog"
   :name     "Simon Gray's blog"
   :language "en"
   :email    "simon@grays.blog"
   :tagline  [:address "My humble place on the web; entirely home-made and up since " [:time {:datetime "2023"} "2023"] "."]
   :author   "Simon Gray"
   :identity {"https://github.com/simongray"                     {:label "Github"}
              "https://indieweb.social/@simongray"               {:label "Mastodon"}
              "https://www.linkedin.com/in/simon-gray-54b8a633/" {:label "LinkedIn"}
              "mailto:simon@grays.blog"                          {:label "Email"}}})

(defn ->connector-map
  [{:keys [development posts-dir] :as conf}]
  (let [csp (if development
              {:default-src "'self' 'unsafe-inline' 'unsafe-eval' https://rsms.me/inter/ localhost:* 0.0.0.0:* ws://localhost:* ws://0.0.0.0:*"}
              {:default-src "'self'"
               :font-src    "'self' https://rsms.me/inter/"
               :style-src   "'self' 'unsafe-inline' https://rsms.me/inter/"
               :base-uri    "'self'"})]
    (-> (conn/default-connector-map "0.0.0.0" 4567)
        ;; CSP and (dev-only) permissive CORS are configured here.
        (conn/with-default-interceptors
          :secure-headers {:content-security-policy-settings csp}
          ;; Make sure we can communicate with the Shadow CLJS app during dev.
          :allowed-origins (when development (constantly true)))
        ;; Attach conf to every request before routing/handlers run.
        (conn/with-interceptor (i/add-conf conf))
        ;; Posts live under "/posts/" so their two-segment permalinks don't
        ;; collide with root-level resource paths like "/css/main.css".
        (conn/with-routes
          #{["/" :get [i/frontpage] :route-name ::frontpage]
            ["/posts/:year/:slug" :get [i/single-post] :route-name ::single-post
             :constraints {:year #"\d\d\d\d"}]
            [shared/feed-path :get [i/atom-feed] :route-name ::feed]
            [shared/feed-path :head [i/atom-feed] :route-name ::feed-head]}
          (resources/file-routes {:file-root (str posts-dir "/assets")
                                  :prefix    "/assets"})
          (resources/resource-routes {:resource-root "public"
                                      :prefix        "/"})))))

(defn start!
  []
  (let [prod-conf (assoc conf
                    :db-dir "/opt/blog/simon.grays.blog/db/"
                    :posts-dir "/opt/blog/simon.grays.blog/posts/")]
    (db/start! prod-conf)
    (-> (->connector-map prod-conf)
        (assoc :join? true)
        (jetty/create-connector nil)
        (conn/start!))))

(defn start-dev!
  []
  (let [dev-conf (assoc conf
                   :development true
                   :db-dir "/Users/simongray/Code/simon.grays.blog/db/"
                   :posts-dir "/Users/simongray/Code/simon.grays.blog/posts/")]
    (db/start! dev-conf)
    (->> (-> (->connector-map dev-conf)
             (jetty/create-connector nil)
             (conn/start!))
         (reset! server))))

(defn stop-dev []
  (conn/stop! @server))

(defn restart!
  []
  (when @server
    (stop-dev))
  (start-dev!))

(defn -main
  [& args]
  (start!))

(comment
  (restart!)
  #_.)
