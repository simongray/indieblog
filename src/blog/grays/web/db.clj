(ns blog.grays.web.db
  "Functions for populating the content database with new entities and watching
  a directory for files to sync (create, update, delete)."
  (:require [clojure.string :as str]
            [datalevin.core :as d]
            [nextjournal.beholder :as beholder]
            [blog.grays.web.content :as content]))

(def schema
  "The Datalevin schema for blog post entities.

  Notes on the less obvious attributes:
  - :file is the natural key (an absolute file path) and doubles as the
    identity/upsert attribute; it replaces Asami's use of :db/ident.
  - :derived is a set of keywords noting which attributes were derived rather
    than read from the source frontmatter; cardinality-many => reads back as a
    set, so (derived :title) keeps working.
  - :hiccup has no :db/valueType on purpose, so Datalevin stores the nested
    Hiccup vector as a single opaque value and returns it as-is (same approach
    as :file/node in the prayer-app project)."
  {:file     {:db/valueType :db.type/string
              :db/unique    :db.unique/identity}
   :ext      {:db/valueType :db.type/string}
   :slug     {:db/valueType :db.type/string}
   :year     {:db/valueType :db.type/string}
   :date     {:db/valueType :db.type/string}
   :title    {:db/valueType :db.type/string
              :db/fulltext  true}
   :language {:db/valueType :db.type/string}
   :location {:db/valueType :db.type/string}
   :length   {:db/valueType :db.type/long}
   :content  {:db/valueType :db.type/string
              :db/fulltext  true}
   :derived  {:db/valueType   :db.type/keyword
              :db/cardinality :db.cardinality/many}
   :hiccup   {:db/doc "Opaque Hiccup value stored as-is (no :db/valueType => not indexed)."}})

(defonce watcher
  (atom nil))

(defonce conns
  ;; db-dir -> Datalevin connection; one connection is held open per directory.
  (atom {}))

(defn pconn
  "Get (opening once and caching) a connection to the Datalevin storage located
  in `db-dir`."
  [db-dir]
  (or (@conns db-dir)
      (let [conn (d/get-conn db-dir schema)]
        (swap! conns assoc db-dir conn)
        conn)))

(defn exists?
  "Does an entity with :file = `file` exist in `db`?"
  [db file]
  (some? (d/entity db [:file file])))

(defn retract-entity!
  "Retracts the post in `conn` identified by `file`, if present."
  [conn file]
  (when (exists? (d/db conn) file)
    (d/transact! conn [[:db/retractEntity [:file file]]])))

(defn set-up-db!
  "Set up a Datalevin db from the :db-dir and :posts-dir found in `conf`."
  [{:keys [db-dir posts-dir] :as conf}]
  (let [conn    (pconn db-dir)
        db      (d/db conn)
        posts   (content/check! (content/md-dossier posts-dir))
        there?  (fn [{:keys [file]}] (exists? db file))
        updates (filter there? posts)
        inserts (remove there? posts)]
    ;; For updates: fully retract the existing entity, then insert the new one.
    ;; The retract clears any attributes dropped from the source frontmatter.
    (doseq [{:keys [file] :as update-post} updates]
      (d/transact! conn [[:db/retractEntity [:file file]]])
      (d/transact! conn [(content/entity-create update-post)]))
    ;; For inserts: just insert normally.
    (when (seq inserts)
      (d/transact! conn (mapv content/entity-create inserts)))))

(defn refresh-post!
  "Force refresh of a post entity in `conn` from `file` by reprocessing its
  source file.

  Useful for fixing corrupted hiccup data or applying content processing
  updates. The `file` should be the absolute path to the markdown file
  (same as :file).

  Example:
    (refresh-post! conn \"/path/to/posts/my-post.md\")"
  [conn file]
  (when-let [fresh-content (content/md-info file)]
    (retract-entity! conn file)
    (d/transact! conn [(content/entity-create fresh-content)])))

(defn ->watcher-callback
  "A callback function that syncs file system updates with a Datalevin `conn`."
  [conn]
  (fn [{:keys [type path] :as m}]
    (let [path (str path)
          ext  (last (str/split path #"\."))]
      (when (content/supported-ext ext)
        (prn m)
        (cond
          ;; Handle markdown files - put in database
          (= ext "md")
          (cond
            (#{:create :modify} type)
            (do
              (retract-entity! conn path)                   ; no-op if absent
              (d/transact! conn [(content/entity-create (content/md-info path))]))

            (= :delete type)
            (retract-entity! conn path))

          ;; TODO: also add some asset metadata to db?
          ;; Asset files are now served directly - no copying needed
          (contains? content/img-ext ext)
          (println "Asset found:" path "- served directly from asset dir"))))))

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
  (let [db (d/db conn)]
    (->> (d/q '[:find [?e ...]
                :where
                [?e :ext "md"]]
              db)
         (map (partial d/entity db))
         (content/sort-posts))))

(defn single-post
  [conn year slug]
  (let [db (d/db conn)]
    (some->> (d/q '[:find ?e .
                    :in $ ?year ?slug
                    :where
                    [?e :year ?year]
                    [?e :slug ?slug]]
                  db year slug)
             (d/entity db))))

(defn search-posts
  "Full-text search `conn` for posts matching the query string `q`.
  Searches the fulltext attributes (:content and :title)."
  [conn q]
  (let [db (d/db conn)]
    (->> (d/q '[:find [?e ...]
                :in $ ?q
                :where
                [(fulltext $ ?q) [[?e _ _]]]]
              db q)
         (distinct)
         (map (partial d/entity db))
         (content/sort-posts))))

(comment
  (start! conf)
  (beholder/stop @watcher)

  ;; Test retrieval of posts
  (->> (latest-posts (pconn "/Users/simongray/Code/simon.grays.blog/db/"))
       (count))

  (single-post (pconn "/Users/simongray/Code/simon.grays.blog/db/")
               "2020" "clojure-the-lisp-that-wants-to-spread")

  ;; Full-text search
  (map :title (search-posts (pconn "/Users/simongray/Code/simon.grays.blog/db/")
                            "clojure"))

  ;; Verify the Hiccup value round-trips as an opaque nested vector
  (:hiccup (d/entity (d/db (pconn "/Users/simongray/Code/simon.grays.blog/db/"))
                     [:file "/Users/simongray/Code/simon.grays.blog/posts/spread.md"]))

  ;; Force refresh a problematic post
  (refresh-post! (pconn "/Users/simongray/Code/simon.grays.blog/db/")
                 "/Users/simongray/Code/simon.grays.blog/posts/spread.md")

  #_.)
