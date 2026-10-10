(ns blog.grays.web.db
  "The content database: a derived index, rebuilt from the files that are the
  actual source of truth.

  Posts come from the markdown files in the :posts-dir; Webmentions, reply
  contexts and native comments from the EDN files in the :indieweb-dir (see the
  indieweb namespace). Nothing else writes here, so the db is kept in memory
  and rebuilt from the files whenever the service starts.

  Both directories are watched, and changes sync straight back in."
  (:require [dk.simongray.drop-in-search :as search]
            [dk.simongray.drop-in-search.queries :as search.queries]
            [nextjournal.beholder :as beholder]
            [taoensso.telemere :as tel]
            [blog.grays.web.component :as c]
            [blog.grays.web.content :as content]
            [blog.grays.web.indieweb :as indieweb]
            [blog.grays.web.shared :as shared]))

(defonce watchers
  ;; The beholder watchers currently running; stopped before starting new ones.
  (atom []))

(def search-opts
  "The defaults of every search of the posts: a match in the title counts
  three times as much as one in the tags or the text."
  {:boosts {:title 3}})

(defonce db
  ;; The db: posts by :file, a full-text search index of them, and the
  ;; IndieWeb entities; one value, swapped as a whole.
  (atom {:posts {} :search (search/index [] search-opts) :indieweb []}))

;;; Posts

(defn post->document
  "The search document of `post`: its title, tags and text, under its :file."
  [post]
  {:id     (:file post)
   :fields {:title (:title post)
            :tags  (:tags post)
            :text  (c/post-text post)}})

(defn with-post
  "The db value `m` with `post` in it, replacing any post with the same :file."
  [m post]
  (-> m
      (assoc-in [:posts (:file post)] post)
      (update :search search/add (post->document post))))

(defn without-post
  "The db value `m` without the post identified by `file`, if present."
  [m file]
  (-> m
      (update :posts dissoc file)
      (update :search search/remove file)))

(defn sync-posts!
  "Sync every post found in the :posts-dir of `conf` into the db, replacing
  the posts already there."
  [{:keys [posts-dir] :as conf}]
  (let [posts (content/check-posts (content/read-posts posts-dir))]
    (swap! db assoc
           :posts (zipmap (map :file posts) posts)
           :search (search/index (map post->document posts) search-opts))
    (tel/log! {:level :info
               :id    ::posts-synced
               :data  {:posts-dir posts-dir
                       :posts     (count posts)}
               :msg   (str "Posts synced: " (count posts) " from " posts-dir)})))

;;; IndieWeb data

(defn sync-indieweb!
  "Sync the IndieWeb files in the :indieweb-dir of `conf` into the db. The
  data is small and its files are rewritten wholesale, so the entire set is
  replaced at once."
  [{:keys [indieweb-dir] :as conf}]
  (let [entities (vec (indieweb/entities indieweb-dir))]
    (swap! db assoc :indieweb entities)
    (tel/log! {:level :info
               :id    ::indieweb-synced
               :data  {:indieweb-dir indieweb-dir
                       :entities  (count entities)}
               :msg   (str "IndieWeb data synced: " (count entities) " from " indieweb-dir)})))

;;; Watching

(defn- ->post-callback
  "A callback function that syncs post file updates with `db`.

  When given, `on-sync` is called with the affected post after each sync, e.g.
  to notify the outside world of the change. For deletions it is called with
  the pre-retraction :year and :slug."
  [db & {:keys [on-sync]}]
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
                                  (swap! db with-post post)
                                  (when on-sync (on-sync post)))
              :delete (let [post (get-in @db [:posts path])]
                        (swap! db without-post path)
                        (when (and on-sync (seq post))
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

(defn- ->edn-callback
  "A callback function that runs `sync!` on `conf` when an EDN file changes.

  Deliberately takes no on-sync hook: a Webmention or comment arriving must not
  be mistaken for a post being published, or receiving one would send
  Webmentions."
  [conf sync!]
  (fn [{:keys [type path] :as event}]
    (when (= "edn" (content/file-ext (str path)))
      (tel/log! {:level :debug, :id ::fs-event, :data event})
      (try
        (sync! conf)
        (catch Throwable t
          (tel/error! {:id ::sync-error, :data {:path (str path), :type type}} t))))))

(defn watch!
  "Watch the :posts-dir and :indieweb-dir of `conf` for file changes, syncing
  them into the db; `on-sync` is called with each synced post, and never with
  IndieWeb changes."
  [{:keys [posts-dir indieweb-dir] :as conf} & {:keys [on-sync]}]
  (run! beholder/stop (first (reset-vals! watchers [])))
  (reset! watchers
          [(beholder/watch (->post-callback db :on-sync on-sync)
                           posts-dir)
           (beholder/watch (->edn-callback conf sync-indieweb!) indieweb-dir)])
  (tel/log! {:level :info
             :id    ::watching
             :data  {:posts-dir posts-dir :indieweb-dir indieweb-dir}
             :msg   (str "Watching for changes in " posts-dir " and " indieweb-dir)}))

;;; Lifecycle

(defn start!
  "Sync the posts and IndieWeb files of `conf` into its db, then watch both
  directories for changes; `on-sync` is passed on to `watch!`."
  [conf & {:keys [on-sync]}]
  (indieweb/ensure-dirs! (:indieweb-dir conf))
  (sync-posts! conf)
  (sync-indieweb! conf)
  (watch! conf :on-sync on-sync))

;;; Queries

(defn posts
  "All posts in `db`, sorted by most recent; excludes the standalone pages."
  [db]
  (->> (vals (:posts @db))
       (remove shared/page?)
       (content/sort-posts)))

(defn tagged-posts
  "All posts in `db` carrying the tag slug `tag`, sorted by most recent;
  excludes the standalone pages."
  [db tag]
  (filter #(contains? (:tags %) tag) (posts db)))

(defn find-post
  "The single post in `db` identified by `year` and `slug`, if present; a
  standalone page is not a post, so its slug misses here (see find-page)."
  [db year slug]
  (when-not (shared/page-slugs slug)
    (->> (vals (:posts @db))
         (filter (comp #{[year slug]} (juxt :year :slug)))
         (first))))

(defn post-at-path
  "The post in `db` at the local permalink `path`, provided the path has
  permalink shape; nil otherwise."
  [db path]
  (when-let [[_ year slug] (some->> path (re-matches shared/post-path-re))]
    (find-post db year slug)))

(defn find-page
  "The standalone page (see shared/page-slugs) with the given `slug` in `db`,
  if present; a page has no year, so the slug alone identifies it."
  [db slug]
  (when (shared/page-slugs slug)
    (->> (vals (:posts @db))
         (filter (comp #{slug} :slug))
         (first))))

(defn pages
  "The standalone pages present in `db`, in the order shared/pages declares."
  [db]
  (keep (partial find-page db) (map :slug shared/pages)))

(defn tag-counts
  "Every tag slug carried by a post in `db`, alphabetically, each with the
  number of posts carrying it."
  [db]
  (->> (posts db)
       (mapcat :tags)
       (frequencies)
       (sort-by key)))

(defn search-posts
  "The posts in `db` matching the query string `q`, best first. Likes and
  reposts are left out, having little text of their own.

  Any words matched with typos are in the metadata under :typos, as in
  search/query."
  [db q]
  (let [{:keys [search] by-file :posts} @db
        results (search/query search q)]
    (-> (->> results
             (map (comp by-file :id))
             (remove (some-fn :like-of :repost-of))
             (vec))
        (with-meta (meta results)))))

(defn excerpt
  "The text of `post` around its matches of the query `q` in `db`, as parts
  to render: maps of :text, with :match? on the matches."
  [db post q]
  (search.queries/snippet (c/post-text post)
                          q
                          (search/query-opts (:search @db))))

(defn verified-mentions
  "All verified mentions in `db` of the post at the permalink `path`, sorted
  by publication."
  [db path]
  (->> (:indieweb @db)
       (filter (comp #{path} :mention/target))
       (filter (comp #{:verified} :mention/status))
       (sort-by (juxt :mention/published :mention/received))))

(defn approved-comments
  "All approved comments in `db` on the post at the permalink `path`, sorted
  by publication."
  [db path]
  (->> (:indieweb @db)
       (filter (comp #{path} :comment/target))
       (filter (comp #{:approved} :comment/status))
       (sort-by (juxt :comment/published :comment/received))))

(defn delivery-targets
  "The targets previously delivered Webmentions with the post at the permalink
  `path` as their source."
  [db path]
  (->> (:indieweb @db)
       (filter (comp #{path} :delivery/source))
       (map :delivery/target)))

(defn find-context
  "The cached reply context of `url` in `db`, if previously fetched."
  [db url]
  (->> (:indieweb @db)
       (filter (comp #{url} :context/url))
       (first)))

(comment
  (require '[blog.grays.web.service :as service])
  (def conf service/dev-conf)

  (start! conf)
  (run! beholder/stop @watchers)

  (count (posts db))
  (find-post db "2020" "clojure-the-lisp-that-wants-to-spread")
  (map :title (search-posts db "clojure"))
  #_.)
