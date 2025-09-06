(ns blog.grays.web.db
  "Functions for populating the content database with new entities and watching
  a directory for files to sync (create, update, delete)."
  (:require [clojure.string :as str]
            [asami.core :as d]
            [nextjournal.beholder :as beholder]
            [blog.grays.web.content :as content]))

(defonce watcher
  (atom nil))

(defn- puri
  [db-dir]
  (str "asami:local://" db-dir))

(defn pconn
  "Get a connection to the persisted storage graph located in `db-dir`."
  [db-dir]
  (d/connect (puri db-dir)))

(defn set-up-db!
  "Set up an Asami db from the :db-dir and :posts-dir found in `conf`."
  [{:keys [db-dir posts-dir] :as conf}]
  (let [conn     (pconn db-dir)
        posts    (content/check! (content/md-dossier posts-dir))
        existing (->> (map :file posts)
                      (filter (partial d/entity conn))
                      (set))
        exists?  (comp existing :file)
        updates  (filter exists? posts)
        inserts  (remove exists? posts)]
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

(defn refresh-post!
  "Force refresh of a post entity in `conn` from `ident` by reprocessing its 
  source file.
  
  Useful for fixing corrupted hiccup data or applying content processing 
  updates. The `ident` should be the absolute path to the markdown file 
  (same as :db/ident).
  
  Example:
    (refresh-post! conn \"/path/to/posts/my-post.md\")"
  [conn ident]
  (when-let [fresh-content (content/md-info ident)]
    (retract-entity! conn ident)
    (d/transact conn {:tx-data [(content/entity-create fresh-content)]})))

(defn ->watcher-callback
  "A callback function that syncs file system updates with an Asami `conn`."
  [conn]
  (fn [{:keys [type path] :as m}]
    (let [path (str path)
          ext  (last (str/split path #"\."))]
      (when (content/supported-ext ext)
        (prn m)
        (let [existing (d/entity conn path)]
          (cond
            (#{:create :modify} type)
            (let [info   (if (= ext "md")
                           (content/md-info path)
                           (content/img-info path))
                  entity (if existing
                           (content/entity-update info)
                           (content/entity-create info))]
              (d/transact conn {:tx-data [entity]}))

            (and (= :delete type) existing)
            (retract-entity! conn path)))))))

(defn set-up-watcher!
  "Set up a directory Watcher from the :db-dir and :posts-dir found in `conf`.

  The data in the :db-dir and the :posts-dir will be synced such that  the
  input data matches the database state."
  [{:keys [db-dir posts-dir] :as conf}]
  (when-let [existing @watcher]
    (beholder/stop existing))
  (reset! watcher (beholder/watch
                    (->watcher-callback (pconn db-dir))
                    posts-dir)))

(defn start!
  [conf]
  (set-up-db! conf)
  (set-up-watcher! conf))

(defn latest-posts
  [conn]
  (->> conn
       (d/q '[:find [?e ...]
              :where
              [?e :ext "md"]])
       (map (partial d/entity conn))
       (content/sort-posts)))

(defn single-post
  [conn year slug]
  (->> conn
       (d/q [:find '[?e ...]
             :where
             ['?e :year year]
             ['?e :slug slug]])
       (map (partial d/entity conn))
       (first)))

(comment
  (start! conf)
  (beholder/stop @watcher)

  ;; Test retrieval of posts
  (->> (latest-posts (pconn "/Users/simongray/Code/simon.grays.blog/db/"))
       (count))

  (single-post (pconn "/Users/simongray/Code/simon.grays.blog/db/")
               "2020" "clojure-the-lisp-that-wants-to-spread")

  ;; Force refresh a problematic post
  (refresh-post! (pconn "/Users/simongray/Code/simon.grays.blog/db/")
                 "/Users/simongray/Code/simon.grays.blog/posts/spread.md")
  #_.)
