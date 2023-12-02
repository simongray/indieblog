(ns blog.grays.web.service
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.ring-middlewares :as rm]
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
   :messages {:finished [[:<> "♪ This the end" [:br] "My only friend, the end ♫"]
                         "Thank you for reading all of that!"
                         "This page is out of words."
                         "It's supposed to look like print."
                         "Done! Try visiting another page."]}
   :author   "Simon Gray"
   :identity {"https://github.com/simongray"                     {:label "Github"}
              "https://indieweb.social/@simongray"               {:label "Mastodon"}
              "https://www.linkedin.com/in/simon-gray-54b8a633/" {:label "LinkedIn"}
              "mailto:simon@grays.blog"                          {:label "Email"}}})

(defn routes
  []
  (route/expand-routes
    #{["/" :get [i/frontpage] :route-name ::frontpage]
      ["/:year/:slug" :get [i/single-post] :route-name ::single-post
       :constraints {:year #"\d\d\d\d"}]
      [shared/feed-path :get [i/atom-feed] :route-name ::feed]

      ;; TODO: is this even needed?
      [shared/feed-path :head [i/atom-feed (rm/head)] :route-name ::feed-head]}))

(defn ->service-map
  [{:keys [development] :as conf}]
  (let [csp (if development
              {:default-src "'self' 'unsafe-inline' 'unsafe-eval' https://rsms.me/inter/ localhost:* 0.0.0.0:* ws://localhost:* ws://0.0.0.0:*"}
              {:default-src "'self'"
               :font-src    "'self' https://rsms.me/inter/"
               :style-src   "'self' 'unsafe-inline' https://rsms.me/inter/"
               :base-uri    "'self'"})]
    (-> {::http/routes         #((deref #'routes))
         ::http/type           :jetty
         ::http/host           "0.0.0.0"
         ::http/port           4567
         ::http/resource-path  "/public"
         ::http/router         :linear-search               ; fix route subsume
         ::http/secure-headers {:content-security-policy-settings csp}}

        ;; Extending default interceptors here.l
        (http/default-interceptors)
        (update ::http/interceptors conj (i/add-conf conf))

        (cond->
          ;; Make sure we can communicate with the Shadow CLJS app during dev.
          development (assoc ::http/allowed-origins (constantly true))))))

(defn start!
  []
  (let [prod-conf (assoc conf
                    :db-dir "/opt/blog/simon.grays.blog/db/"
                    :posts-dir "/opt/blog/simon.grays.blog/posts/")]
    (db/start! prod-conf)
    (-> (->service-map prod-conf)
        (http/create-server)
        (http/start))))

(defn start-dev!
  []
  (let [dev-conf (assoc conf
                   :development true
                   :db-dir "/Users/simongray/Code/simon.grays.blog/db/"
                   :posts-dir "/Users/simongray/Code/simon.grays.blog/posts/")]
    (db/start! dev-conf)
    (->> (assoc (->service-map dev-conf)
           ::http/join? false)
         (http/create-server)
         (http/start)
         (reset! server))))

(defn stop-dev []
  (http/stop @server))

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
