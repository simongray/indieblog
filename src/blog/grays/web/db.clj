(ns blog.grays.web.db
  "The content database: a derived index, rebuilt from the files that are the
  actual source of truth.

  Posts come from the markdown files in the :posts-dir; Webmentions and reply
  contexts from the EDN files in the :indieweb-dir (see the indieweb namespace).
  Nothing else writes here, so the db may be wiped and rebuilt at any time —
  which is what `rebuild!` does, and why schema changes need no migration.

  Both directories are watched, and changes sync straight back in."
  (:require [clojure.java.io :as io]
            [datalevin.core :as d]
            [nextjournal.beholder :as beholder]
            [taoensso.telemere :as tel]
            [blog.grays.web.content :as content]
            [blog.grays.web.indieweb :as indieweb]))

(def schema
  "The Datalevin schema for blog post, webmention and reply context entities.

  Posts are identified by :file (an absolute path). Webmentions and contexts
  need no identity attribute at all: they are never upserted, only replaced
  wholesale by `sync-indieweb!`, and their files already index them. Throughout,
  the local side of a webmention is a permalink path and the remote side an
  absolute URL.

  Notes on the less obvious attributes:
  - :derived is a set of keywords noting which attributes were derived rather
    than read from the source frontmatter; cardinality-many => reads back as a
    set, so (derived :title) keeps working.
  - :hiccup has no :db/valueType on purpose, so Datalevin stores the nested
    Hiccup vector as a single opaque value and returns it as-is."
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
   :reply-to {:db/valueType :db.type/string}
   ;; The other response verbs, each the single URL it acts on. Like :reply-to,
   ;; they are rendered as u-* markup and become Webmention targets in
   ;; send-webmentions!.
   :like-of     {:db/valueType :db.type/string}
   :repost-of   {:db/valueType :db.type/string}
   :bookmark-of {:db/valueType :db.type/string}
   ;; POSSE copies of the post (space-separated URLs), e.g. on Mastodon;
   ;; rendered as hidden u-syndication links for Bridgy et al. to discover.
   :syndication {:db/valueType :db.type/string}
   :length   {:db/valueType :db.type/long}
   :content  {:db/valueType :db.type/string
              :db/fulltext  true}
   ;; Slugs, parsed from the comma-separated tags: frontmatter by
   ;; content/parse-tags; cardinality-many => reads back as a set. The slug is
   ;; both the stored value and the /tags/<slug> URL, so no display form is kept.
   :tags     {:db/valueType   :db.type/string
              :db/cardinality :db.cardinality/many}
   :derived  {:db/valueType   :db.type/keyword
              :db/cardinality :db.cardinality/many}
   :hiccup   {:db/doc "Opaque Hiccup value stored as-is (no :db/valueType => not indexed)."}

   ;; Webmentions received from other sites; the details are parsed from the
   ;; source's microformats during verification.
   ;;
   ;; :mention/source is the URL that was POSTed to us; :mention/url the
   ;; permalink that page claims for itself. Usually the same, but a bridge
   ;; (Bridgy, Bridgy Fed) POSTs a proxy page on its own domain, so we verify
   ;; against the source and display the url. Otherwise every reply from the
   ;; fediverse reads as having come from brid.gy.
   :mention/source       {:db/valueType :db.type/string}
   :mention/url          {:db/valueType :db.type/string}
   :mention/target       {:db/valueType :db.type/string}
   :mention/status       {:db/valueType :db.type/keyword} ; :pending :verified :failed :blocked
   :mention/kind         {:db/valueType :db.type/keyword} ; :reply :like :repost :bookmark :mention
   :mention/received     {:db/valueType :db.type/string}
   :mention/published    {:db/valueType :db.type/string}
   :mention/author-name  {:db/valueType :db.type/string}
   :mention/author-url   {:db/valueType :db.type/string}
   :mention/author-photo {:db/valueType :db.type/string}
   ;; The local, self-served copy of :mention/author-photo, written only when the
   ;; fetch succeeded; its presence is also what tells the facepile it has a face
   ;; to show. A path, not a URL, since we serve it ourselves (see service.clj).
   :mention/author-photo-cache {:db/valueType :db.type/string}
   :mention/content      {:db/valueType :db.type/string} ; an excerpt; replies only

   ;; Webmentions we delivered to other sites: previously notified targets must
   ;; be re-notified when a post is updated or deleted (per the spec), which is
   ;; also how removed links propagate as deletions.
   :delivery/source {:db/valueType :db.type/string}
   :delivery/target {:db/valueType :db.type/string}
   :delivery/at     {:db/valueType :db.type/string}
   :delivery/status {:db/valueType :db.type/string}

   ;; Reply contexts fetched from the :reply-to URL of a post; failures are
   ;; cached too (as an entity without title/author).
   :context/url     {:db/valueType :db.type/string}
   :context/title   {:db/valueType :db.type/string}
   :context/author  {:db/valueType :db.type/string}
   :context/fetched {:db/valueType :db.type/string}})

(def response-verb-attrs
  "The frontmatter attributes naming a URL the post responds to. Each is
  rendered as u-* markup (see component/article) and sent a Webmention (see
  webmention/send-webmentions!)."
  [:reply-to :like-of :repost-of :bookmark-of])

(defn response-post?
  "Is `post` a response (a like, repost, bookmark or reply) rather than an
  article, i.e. does it carry any response verb?"
  [post]
  (boolean (some post response-verb-attrs)))

(defonce watchers
  ;; The beholder watchers currently running; stopped before starting new ones.
  (atom []))

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

;;; Posts

(defn retract-post!
  "Retract the post in `conn` identified by `file`, if present."
  [conn file]
  (when (d/entity (d/db conn) [:file file])
    (d/transact! conn [[:db/retractEntity [:file file]]])))

(defn put-post!
  "Insert `post` into `conn`, replacing any existing post with the same :file.

  The full retraction (a no-op for new posts) clears any attributes dropped
  from the source frontmatter."
  [conn post]
  (retract-post! conn (:file post))
  (d/transact! conn [post]))

(defn refresh-post!
  "Refresh the post entity in `conn` by reprocessing its source markdown `file`;
  a REPL convenience for applying content processing updates."
  [conn file]
  (when-let [post (content/md->post file)]
    (put-post! conn post)))

(defn sync-posts!
  "Sync every post found in the :posts-dir of `conf` into the Datalevin db
  located in its :db-dir."
  [{:keys [db-dir posts-dir] :as conf}]
  (let [conn  (get-conn db-dir)
        posts (content/check! (content/read-posts posts-dir))]
    (run! (partial put-post! conn) posts)
    (tel/log! {:level :info
               :id    ::posts-synced
               :data  {:posts-dir posts-dir
                       :posts     (count posts)}
               :msg   (str "Posts synced: " (count posts) " from " posts-dir)})))

;;; IndieWeb data

(defn sync-indieweb!
  "Sync the IndieWeb files in the :indieweb-dir of `conf` into the Datalevin
  db located in its :db-dir, replacing whatever was there.

  The data is small and its files are rewritten wholesale, so the entire set
  is replaced in a single transaction; per-file diffing would buy nothing but
  a deletion bug."
  [{:keys [db-dir indieweb-dir] :as conf}]
  (let [conn     (get-conn db-dir)
        db       (d/db conn)
        entities (indieweb/entities indieweb-dir)
        stale    (for [attr [:mention/source :delivery/source :context/url]
                       eid  (d/q '[:find [?e ...]
                                   :in $ ?attr
                                   :where [?e ?attr]]
                                 db attr)]
                   [:db/retractEntity eid])]
    (d/transact! conn (concat stale entities))
    (tel/log! {:level :info
               :id    ::indieweb-synced
               :data  {:indieweb-dir indieweb-dir
                       :entities  (count entities)}
               :msg   (str "IndieWeb data synced: " (count entities) " from " indieweb-dir)})))

;;; Watching

(defn- ->post-callback
  "A callback function that syncs post file updates with a Datalevin `conn`.

  When given, `on-sync` is called with the affected post after each sync —
  for deletions, with its pre-retraction :year and :slug — e.g. to notify
  the outside world of the change."
  [conn & {:keys [on-sync]}]
  (fn [{:keys [type path] :as event}]
    (let [path (str path)
          ext  (content/file-ext path)]
      (when (content/supported-ext ext)
        (tel/log! {:level :debug, :id ::fs-event, :data event})
        (try
          (cond
            (= "md" ext)
            (case type
              (:create :modify) (let [post (content/md->post path)]
                                  (put-post! conn post)
                                  (when on-sync (on-sync post)))
              :delete (let [post (into {} (d/entity (d/db conn) [:file path]))]
                        (retract-post! conn path)
                        (when (and on-sync (:year post))
                          (on-sync post)))
              nil)

            ;; TODO: also add some asset metadata to db?
            ;; Asset files are served straight from disk (see the file-routes
            ;; in the service ns), so there is nothing to sync.
            (content/img-ext ext)
            (tel/log! {:level :info
                       :id    ::asset-found
                       :data  {:path path}
                       :msg   (str "Asset found (served directly): " path)}))
          ;; Never let a single bad file kill the watcher thread; log and move on.
          (catch Throwable t
            (tel/error! {:id ::sync-error, :data {:path path, :type type}} t)))))))

(defn- ->indieweb-callback
  "A callback function that syncs IndieWeb file updates into the db of `conf`.

  Deliberately takes no on-sync hook: a Webmention arriving must not be mistaken
  for a post being published, or receiving one would send one."
  [conf]
  (fn [{:keys [type path] :as event}]
    (when (= "edn" (content/file-ext (str path)))
      (tel/log! {:level :debug, :id ::fs-event, :data event})
      (try
        (sync-indieweb! conf)
        (catch Throwable t
          (tel/error! {:id ::sync-error, :data {:path (str path), :type type}} t))))))

(defn watch!
  "Watch the :posts-dir and :indieweb-dir of `conf` for file changes, syncing them
  into the Datalevin db located in its :db-dir; `on-sync` is called with each
  synced post, and never with IndieWeb changes."
  [{:keys [db-dir posts-dir indieweb-dir] :as conf} & {:keys [on-sync]}]
  (run! beholder/stop (first (reset-vals! watchers [])))
  (reset! watchers
          [(beholder/watch (->post-callback (get-conn db-dir) :on-sync on-sync)
                           posts-dir)
           (beholder/watch (->indieweb-callback conf) indieweb-dir)])
  (tel/log! {:level :info
             :id    ::watching
             :data  {:posts-dir posts-dir :indieweb-dir indieweb-dir}
             :msg   (str "Watching for changes in " posts-dir " and " indieweb-dir)}))

;;; Lifecycle

(defn start!
  [conf & {:keys [on-sync]}]
  (indieweb/ensure-dirs! (:indieweb-dir conf))
  (sync-posts! conf)
  (sync-indieweb! conf)
  (watch! conf :on-sync on-sync))

(defn rebuild!
  "Delete the Datalevin db in the :db-dir of `conf` and rebuild it from the
  files in its :posts-dir and :indieweb-dir.

  Those files are the source of truth, so this is always safe — and it is how
  a schema change is applied."
  [{:keys [db-dir] :as conf}]
  (when-let [conn (@conns db-dir)]
    (d/close conn)
    (swap! conns dissoc db-dir))
  (run! io/delete-file (reverse (file-seq (io/file db-dir))))
  (tel/log! {:level :info
             :id    ::db-deleted
             :data  {:db-dir db-dir}
             :msg   (str "Deleted the db in " db-dir "; rebuilding from files")})
  (sync-posts! conf)
  (sync-indieweb! conf))

;;; Queries

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

(defn get-posts-by-tag
  "All posts in `conn` carrying the tag slug `tag`, sorted by most recent."
  [conn tag]
  (let [db (d/db conn)]
    (->> (d/q '[:find [?e ...]
                :in $ ?tag
                :where
                [?e :ext "md"]
                [?e :tags ?tag]]
              db tag)
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

(defn get-mentions
  "All verified mentions in `conn` of the post at the permalink `path`, sorted
  by publication."
  [conn path]
  (let [db (d/db conn)]
    (->> (d/q '[:find [?e ...]
                :in $ ?path
                :where
                [?e :mention/target ?path]
                [?e :mention/status :verified]]
              db path)
         (map (partial d/entity db))
         (sort-by (juxt :mention/published :mention/received)))))

(defn get-delivery-targets
  "The targets previously delivered Webmentions with the post at the permalink
  `path` as their source."
  [conn path]
  (d/q '[:find [?target ...]
         :in $ ?path
         :where
         [?e :delivery/source ?path]
         [?e :delivery/target ?target]]
       (d/db conn) path))

(defn get-context
  "The cached reply context of `url` in `conn`, if previously fetched."
  [conn url]
  (let [db (d/db conn)]
    (some->> (d/q '[:find ?e .
                    :in $ ?url
                    :where
                    [?e :context/url ?url]]
                  db url)
             (d/entity db))))

(comment
  (require '[blog.grays.web.service :as service])
  (def conf service/dev-conf)
  (def conn (get-conn (:db-dir conf)))

  (start! conf)
  (run! beholder/stop @watchers)

  ;; Wipe and rebuild from the files; how a schema change is applied.
  (rebuild! conf)

  (count (get-posts conn))
  (get-post conn "2020" "clojure-the-lisp-that-wants-to-spread")
  (map :title (search-posts conn "clojure"))

  ;; Verify the Hiccup value round-trips as an opaque nested vector
  (:hiccup (d/entity (d/db conn)
                     [:file "/Users/simongray/Code/simon.grays.blog/posts/spread.md"]))
  #_.)
