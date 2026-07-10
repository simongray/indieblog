(ns blog.grays.web.db
  "Functions for populating the content database with new entities and watching
  a directory for files to sync (create, update, delete)."
  (:require [datalevin.core :as d]
            [nextjournal.beholder :as beholder]
            [taoensso.telemere :as tel]
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

(defn get-conn
  "Get (opening once and caching) a connection to the Datalevin storage located
  in `db-dir`."
  [db-dir]
  (or (@conns db-dir)
      (let [conn (d/get-conn db-dir schema)]
        (swap! conns assoc db-dir conn)
        conn)))

(defn retract-post!
  "Retract the post in `conn` identified by `file`, if present."
  [conn file]
  (when (d/entity (d/db conn) [:file file])
    (d/transact! conn [[:db/retractEntity [:file file]]])))

(defn put-post!
  "Insert `post` into `conn`, replacing any existing post with the same :file.

  The full retraction (a no-op for new posts) clears any attributes dropped
  from the source frontmatter."
  [conn {:keys [file] :as post}]
  (retract-post! conn file)
  (d/transact! conn [post]))

(defn sync-posts!
  "Sync every post found in the :posts-dir of `conf` into the Datalevin db
  located in its :db-dir."
  [{:keys [db-dir posts-dir] :as conf}]
  (let [conn  (get-conn db-dir)
        posts (content/check! (content/read-posts posts-dir))]
    (run! (partial put-post! conn) posts)
    (tel/log! {:level :info
               :id    ::db-ready
               :data  {:posts-dir posts-dir
                       :posts     (count posts)}
               :msg   (str "Content DB ready: " (count posts) " post(s) from " posts-dir)})))

(defn refresh-post!
  "Force refresh of a post entity in `conn` from `file` by reprocessing its
  source file.

  Useful for fixing corrupted hiccup data or applying content processing
  updates. The `file` should be the absolute path to the markdown file
  (same as :file).

  Example:
    (refresh-post! conn \"/path/to/posts/my-post.md\")"
  [conn file]
  (when-let [post (content/md->post file)]
    (put-post! conn post)))

(defn ->watcher-callback
  "A callback function that syncs file system updates with a Datalevin `conn`."
  [conn]
  (fn [{:keys [type path] :as event}]
    (let [path (str path)
          ext  (content/file-ext path)]
      (when (content/supported-ext ext)
        (tel/log! {:level :debug, :id ::fs-event, :data event})
        (try
          (cond
            ;; Handle markdown files - put in database
            (= "md" ext)
            (case type
              (:create :modify) (put-post! conn (content/md->post path))
              :delete (retract-post! conn path)
              nil)

            ;; TODO: also add some asset metadata to db?
            ;; Asset files are now served directly - no copying needed
            (content/img-ext ext)
            (tel/log! {:level :info
                       :id    ::asset-found
                       :data  {:path path}
                       :msg   (str "Asset found (served directly): " path)}))
          ;; Never let a single bad file kill the watcher thread; log and move on.
          (catch Throwable t
            (tel/error! {:id ::sync-error, :data {:path path, :type type}} t)))))))

(defn watch-posts!
  "Watch the :posts-dir of `conf` for file changes, syncing them into the
  Datalevin db located in its :db-dir."
  [{:keys [db-dir posts-dir] :as conf}]
  (when-let [existing @watcher]
    (beholder/stop existing))
  (reset! watcher (beholder/watch
                    (->watcher-callback (get-conn db-dir))
                    posts-dir))
  (tel/log! {:level :info
             :id    ::watching
             :data  {:posts-dir posts-dir}
             :msg   (str "Watching for post changes in " posts-dir)}))

(defn start!
  [conf]
  (sync-posts! conf)
  (watch-posts! conf))

(defn get-posts
  "All posts in `conn`, sorted by most recent."
  [conn]
  (let [db (d/db conn)]
    (->> (d/q '[:find [?e ...]
                :where
                [?e :ext "md"]]
              db)
         (map (partial d/entity db))
         (content/sort-posts))))

(defn get-post
  "The single post in `conn` identified by `year` and `slug`, if present."
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
  (->> (get-posts (get-conn "/Users/simongray/Code/simon.grays.blog/db/"))
       (count))

  (get-post (get-conn "/Users/simongray/Code/simon.grays.blog/db/")
            "2020" "clojure-the-lisp-that-wants-to-spread")

  ;; Full-text search
  (map :title (search-posts (get-conn "/Users/simongray/Code/simon.grays.blog/db/")
                            "clojure"))

  ;; Verify the Hiccup value round-trips as an opaque nested vector
  (:hiccup (d/entity (d/db (get-conn "/Users/simongray/Code/simon.grays.blog/db/"))
                     [:file "/Users/simongray/Code/simon.grays.blog/posts/spread.md"]))

  ;; Force refresh a problematic post
  (refresh-post! (get-conn "/Users/simongray/Code/simon.grays.blog/db/")
                 "/Users/simongray/Code/simon.grays.blog/posts/spread.md")

  #_.)
