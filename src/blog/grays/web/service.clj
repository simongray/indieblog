(ns blog.grays.web.service
  (:require [clojure.string :as str]
            [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [asami.core :as d]
            [nextjournal.beholder :as beholder]
            [blog.grays.web.content :as content]
            [blog.grays.web.interceptors :as i])
  (:gen-class))

(defn- puri
  [db-dir]
  (str "asami:local://" db-dir))

(defn pconn
  "Get a connection to the persisted storage graph located in `db-dir`."
  [db-dir]
  (d/connect (puri db-dir)))

(defonce server
  (atom nil))

(defonce watcher
  (atom nil))

(def development?
  true)

(def conf
  {:title       "Simon Gray's blog"
   :identity    {"https://github.com/simongray"       {:label "Github"}
                 "https://twitter.com/simongraysays"  {:label "Twitter"}
                 "https://indieweb.social/@simongray" {:label "Mastodon"}
                 "mailto:simon@grays.blog"            {:label "Email"}}
   :db-dir      "test/resources/db/"
   :content-dir "test/resources/articles/"})

(defn routes
  [conf]
  (route/expand-routes
    #{["/" :get [(i/add-conf conf) i/root] :route-name ::root]}))

(defn ->service-map
  [conf]
  (let [csp (if development?
              {:default-src "'self' 'unsafe-inline' 'unsafe-eval' localhost:* 0.0.0.0:* ws://localhost:* ws://0.0.0.0:*"}
              {:default-src "'self'"
               :base-uri    "'self'"})]
    (cond-> {::http/routes         #((deref #'routes) conf)
             ::http/type           :jetty
             ::http/host           "0.0.0.0"
             ::http/port           4567
             ::http/resource-path  "/public"
             ::http/secure-headers {:content-security-policy-settings csp}}

      ;; Make sure we can communicate with the Shadow CLJS app during dev.
      development? (assoc ::http/allowed-origins (constantly true)))))

(defn set-up-db!
  "Set up an Asami db from the :db-dir and :content-dir found in `conf`."
  [{:keys [db-dir content-dir] :as conf}]
  (let [conn     (pconn db-dir)
        articles (content/check! (content/md-articles content-dir))
        existing (->> (map (comp :file meta) articles)
                      (filter (partial d/entity conn))
                      (set))
        exists?  (comp existing :file meta)
        updates  (filter exists? articles)
        inserts  (remove exists? articles)]
    (d/transact conn {:tx-data (map content/entity-update updates)})
    (d/transact conn {:tx-data (map content/entity-create inserts)})))

(defn entity-triples
  "Find the triples in `conn` of the entity identified by `ident` (:db/ident)."
  [conn ident]
  (d/q '[:find ?e ?a ?v
         :in $ ?ident
         :where
         [?e :db/ident ?ident]
         [?e ?a ?v]]
       conn ident))

(defn- retracted-eav
  [[e a v]]
  [:db/retract e a v])

(defn retract-entity!
  "Retracts the entity in `conn` identified by `ident`."
  [conn ident]
  (when-let [triples (entity-triples conn ident)]
    (d/transact conn {:tx-data (map retracted-eav triples)})))

(defn ->watcher-callback
  "A callback function that syncs file system updates with an Asami `conn`."
  [conn]
  (fn [{:keys [type path] :as m}]
    (let [path (str path)
          [_ ext] (str/split path #"\.")]
      (when (= "md" ext)
        (prn m)
        (let [existing (d/entity conn path)]
          (cond
            (#{:create :modify} type)
            (let [article (content/md-article path)
                  entity  (if existing
                            (content/entity-update article)
                            (content/entity-create article))]
              (d/transact conn {:tx-data [entity]}))

            (and (= :delete type) existing)
            (retract-entity! conn path)))))))

(defn set-up-watcher!
  "Set up a directory Watcher from the :db-dir and :content-dir found in `conf`.

  The data in the :db-dir and the :content-dir will be synced such that  the
  input data matches the database state."
  [{:keys [db-dir content-dir] :as conf}]
  (when-let [existing @watcher]
    (beholder/stop watcher))
  (reset! watcher (beholder/watch
                    (->watcher-callback (pconn db-dir))
                    content-dir)))

(defn set-up!
  [conf]
  (set-up-db! conf)
  (set-up-watcher! conf))

(defn start
  [conf]
  (set-up! conf)
  (-> (->service-map conf)
      (http/create-server)
      (http/start)))

(defn start-dev
  [conf]
  (set-up! conf)
  (->> (assoc (->service-map conf)
         ::http/join? false)
       (http/create-server)
       (http/start)
       (reset! server)))

(defn stop-dev []
  (http/stop @server))

(defn restart
  [conf]
  (when @server
    (stop-dev))
  (start-dev conf))

(defn -main
  [& args]
  (start conf))

(comment
  ;; Test retrieval of articles
  (->> (d/q '[:find [?e ...]
              :where [?e :file ?f]]
            (pconn "test/resources/db/"))
       (map (partial d/entity (pconn "test/resources/db/")))
       (map #(dissoc % :hiccup))
       #_(count))

  (set-up! conf)
  (restart conf)
  (beholder/stop @watcher)
  #_.)
