(ns blog.grays.web.indieweb.webmention
  "Sending and receiving Webmentions (https://www.w3.org/TR/webmention/),
  with webmention-clj.

  Sending is driven by the post watcher via `schedule-notify!` where conf
  has :send-webmentions?, and by REPL otherwise. Receiving stores incoming
  mentions as :pending and verifies them asynchronously; only :verified
  mentions are ever displayed. Everything learned here is written to the
  :indieweb-dir as files and the watcher syncs it into the db: reads go to
  the db and writes to the files, never the other way round. The WebSub hub
  ping lives here too, since it shares the HTTP plumbing."
  (:require [clojure.string :as str]
            [taoensso.telemere :as tel]
            [dk.simongray.webmention :as webmention]
            [dk.simongray.webmention.mention :as mention]
            [dk.simongray.websub :as websub]
            [blog.grays.web.db :as db]
            [blog.grays.web.http :as http]
            [blog.grays.web.shared :as shared]
            [blog.grays.web.indieweb :as indieweb]
            [blog.grays.web.indieweb.webmention.html :as html])
  (:import [java.time Instant]
           [java.util.concurrent Executors ScheduledExecutorService TimeUnit]))

(defonce fetcher
  ;; A small fixed pool doubles as backpressure against verification floods.
  (delay (Executors/newFixedThreadPool 2)))

(defn fetch-page!
  "Fetch the page at `url` and parse its HTML; nil when it cannot be read."
  [url]
  (try
    (let [{:keys [body] :as response} (http/get! url)]
      (when (http/ok? response)
        (html/parse body (:url response))))
    (catch Exception e
      (tel/log! {:level :info
                 :id    ::fetch-error
                 :data  {:url url}
                 :msg   (str "Could not fetch " url ": " (ex-message e))})
      nil)))

(defn status-level
  "The log level appropriate for an HTTP `status`: 2xx is expected."
  [status]
  (if (<= 200 status 299) :info :warn))

(def wm-opts
  "The options of webmention-clj's requests: through our own client, with
  our User-Agent."
  {:send       #'http/send!
   :user-agent http/user-agent})

;;; Sending

(defn external-links
  "The URLs linked from the :hiccup of `post`, resolved against its
  permalink, other than those under our own `url`."
  [url {:keys [hiccup year slug] :as post}]
  (->> (webmention/links hiccup (str url (shared/post-href year slug)))
       (remove #(str/starts-with? % url))
       (distinct)))

(defn send-webmention!
  "Send a Webmention of `source` to `target`, whose endpoint webmention-clj
  discovers: the HTTP status of the endpoint's answer, :no-endpoint, or
  :error when the target or the endpoint can't be reached."
  [source target]
  (let [{:keys [outcome status endpoint error]} (webmention/send! source target wm-opts)]
    (cond
      status
      (do (tel/log! {:level (status-level status)
                     :id    ::sent
                     :data  {:endpoint endpoint :source source :target target :status status}
                     :msg   (str "Webmention " source " -> " target ": " status)})
          status)

      (= :no-endpoint outcome)
      :no-endpoint

      :else
      (do (tel/log! {:level :warn
                     :id    ::send-error
                     :data  {:source source :target target :error error}
                     :msg   (str "Could not send Webmention " source " -> " target
                                 ": " (:reason error))})
          :error))))

(defn send-webmentions!
  "Discover endpoints and send Webmentions for every external link in the
  post at `year`/`slug` in `db`, its permalink under the :url of `conf` as
  the source; the URLs the post responds to, the :bridgy-fed bridge, and
  every previously notified target are also targets, which is how updates
  and deletions propagate per the spec. Deliveries are recorded in the
  :indieweb-dir. Returns target -> status (an HTTP status, :no-endpoint, or
  :error)."
  [db {:keys [url indieweb-dir bridgy-fed] :as conf} year slug]
  (let [path    (shared/post-href year slug)
        source  (str url path)
        post    (db/find-post db year slug)            ; nil once a post is deleted
        targets (distinct (concat (db/delivery-targets db path)
                                  (keep #(get post %) shared/response-verb-attrs)
                                  ;; A deleted post is never newly announced
                                  ;; to the bridge; the delivery record above
                                  ;; re-sends it, withdrawing the copy.
                                  (when (and post bridgy-fed) [bridgy-fed])
                                  (some->> post (external-links url))))]
    (into {}
          (for [target targets]
            [target (try
                      (let [status (send-webmention! source target)]
                        ;; Whatever the endpoint answered, the target was
                        ;; notified, so updates and deletions go to it too.
                        (when (int? status)
                          (indieweb/put-delivery! indieweb-dir path target
                                                  {:at     (str (Instant/now))
                                                   :status (str status)}))
                        status)
                      (catch Exception e
                        (tel/error! {:id   ::send-error
                                     :data {:source source :target target}}
                                    e)
                        :error))]))))

;;; Receiving

(defn target-path
  "The local permalink path of the absolute `target` URL under our `url`,
  fragment aside, provided it identifies an existing post in `db`."
  [db url target]
  (let [target (first (str/split (str target) #"#" 2))]
    (when (str/starts-with? target url)
      (let [path (subs target (count url))]
        (when (db/post-at-path db path)
          path)))))

(def excerpt-length
  "How much of a reply we keep: enough to read at a glance, not so much that our
  files become a mirror of somebody else's post."
  280)

(defn cache-avatar!
  "Fetch and locally cache the avatar at `photo-url`, returning its served path,
  or nil when there is no url or the fetch fails. Wrapped so that a missing
  avatar never fails the verification it is part of."
  [{:keys [indieweb-dir] :as conf} photo-url]
  (when photo-url
    (try
      (when-let [{:keys [bytes ext]} (http/get-image! photo-url)]
        (indieweb/put-avatar! indieweb-dir photo-url ext bytes))
      (catch Exception e
        (tel/log! {:level :info
                   :id    ::avatar-error
                   :data  {:url photo-url}
                   :msg   (str "Could not cache avatar " photo-url ": " (ex-message e))})
        nil))))

(defn author-attrs
  "The comment author attributes gleaned from the representative h-card of
  the homepage at `me`: the URL itself, plus whatever name and photo (cached
  under `conf`'s :indieweb-dir) the page marks up."
  [conf me]
  (let [{:keys [name photo]} (some-> (fetch-page! me)
                                     (:mf2)
                                     (mention/representative-card me))]
    {:author-url         me
     :author-name        name
     :author-photo       photo
     :author-photo-cache (cache-avatar! conf photo)}))

(defn- verified-mention
  "What we keep of the mention of `target` in the verified `report` of
  webmention-clj under `conf`: its kind, url, author, date and, for a
  reply, an excerpt, as the mention summary reads them."
  [conf report target]
  (let [{:keys [kind url author published content in-reply-to]}
        (mention/summary report {:max-length excerpt-length})
        ;; A page that names its author by URL alone leaves the rest to the
        ;; author's own page.
        author (if (and (:url author) (not (:name author)))
                 (mention/author! report wm-opts)
                 author)
        photo  (:photo author)]
    (shared/compact
      {:status             :verified
       :kind               kind
       :url                url
       :author-name        (:name author)
       :author-url         (:url author)
       :author-photo       photo
       ;; The local copy we actually render; nil (and so dropped) when there
       ;; is no photo or the fetch fails.
       :author-photo-cache (cache-avatar! conf photo)
       :published          published
       ;; Only a reply keeps its content: a like has none, and the e-content
       ;; of a plain mention is somebody else's entire post.
       :content            (when (#{:reply :rsvp} kind)
                             (:text content))
       ;; Our own permalink is in there at every depth, so it goes.
       :in-reply-to        (not-empty (disj (set in-reply-to) target))})))

(defn verify-mention!
  "Fetch `source` and settle the status of its mention of the post at the local
  `path`, with webmention-clj: :verified when the source links to that post's
  permalink under the :url of `conf`, with what verified-mention keeps of it,
  and :failed otherwise, including when a previously verified link has
  disappeared or the source is gone (the spec's deletion mechanism)."
  [{:keys [url indieweb-dir] :as conf} source path]
  (let [target  (str url path)
        report  (webmention/verify! {:source source :target target} wm-opts)
        mention (merge {:status   :failed
                        :received (str (Instant/now))}
                       (when (= :verified (:outcome report))
                         (verified-mention conf report target)))]
    (indieweb/put-mention! indieweb-dir path source mention)
    (tel/log! {:level :info
               :id    ::verified
               :data  (assoc mention :source source :target path)
               :msg   (str "Webmention " source " -> " path ": "
                           (name (:status mention)))})
    (:status mention)))

(defn cache-avatars!
  "Backfill the avatar cache: for every verified mention in the :indieweb-dir
  of `conf` with an :author-photo but no local copy, fetch and cache it,
  then rewrite the mention file so the watcher re-syncs it."
  [{:keys [indieweb-dir] :as conf}]
  (doseq [[path source mention] (indieweb/all-mentions indieweb-dir)
          :when (and (= :verified (:status mention))
                     (:author-photo mention)
                     (not (:author-photo-cache mention)))
          :let  [cache (cache-avatar! conf (:author-photo mention))]
          :when cache]
    (indieweb/put-mention! indieweb-dir path source
                           (assoc mention :author-photo-cache cache))))

(defn receive-mention!
  "Take the Webmention of `target` by `source` under `conf`: store the mention
  as :pending, and hand source verification to the fetcher pool. Returns the
  local post path, or nil when the target isn't one of the posts in `db`.

  Re-received mentions are re-verified, which is the spec's mechanism for
  updates and deletions, except those previously :blocked by moderation."
  [db {:keys [url indieweb-dir] :as conf} source target]
  (when-let [path (target-path db url target)]
    (when (not= :blocked (get-in (indieweb/mentions indieweb-dir path) [source :status]))
      (indieweb/put-mention! indieweb-dir path source
                             {:status   :pending
                              :received (str (Instant/now))})
      (.submit @fetcher ^Runnable #(verify-mention! conf source path))
      path)))

(defn endpoint
  "Our Webmention endpoint under `conf`, webmention-clj's Ring handler over
  `db`: it checks each Webmention at once, per the spec, takes those of our
  posts, and gives each one it takes to receive-mention!."
  [db {:keys [url] :as conf}]
  (webmention/handler
    (assoc wm-opts
           :target-pred #(some? (target-path db url %))
           :schedule!   (fn [{:keys [source target]}]
                          (receive-mention! db conf source target)))))

(defn block-mention!
  "Moderation: mark the mention of the local `path` by `source` as :blocked,
  hiding it and refusing future re-sends. Deleting the entry from the
  IndieWeb file unblocks it."
  [{:keys [indieweb-dir] :as conf} source path]
  (indieweb/put-mention! indieweb-dir path source {:status :blocked}))

;;; Reply contexts

(defn fetch-context!
  "Fetch the reply context (title/author) of `url` and cache it in the
  :indieweb-dir of `conf`. Failures are cached too, as an entry without
  title/author, so that a dead link is not retried on every render. Call this
  directly to retry one anyway."
  [{:keys [indieweb-dir] :as conf} url]
  (let [entry (some-> (fetch-page! url) (html/entry))]
    (indieweb/put-context! indieweb-dir url
                           (merge {:fetched (str (Instant/now))}
                                  (shared/compact
                                    {:title  (:title entry)
                                     :author (get-in entry [:author :name])})))))

(defonce attempted
  ;; The reply-context URLs already fetched this session; without this,
  ;; every render before the watcher syncs would schedule another fetch.
  (atom #{}))

(defn reply-context
  "The cached reply context of `url` in `db`; a cache miss returns nil and
  schedules an asynchronous fetch, at most one per URL, so that a subsequent
  render gets the context."
  [db conf url]
  (when url
    (or (db/find-context db url)
        (let [[prior _] (swap-vals! attempted conj url)]
          (when-not (prior url)
            (.submit @fetcher ^Runnable #(fetch-context! conf url)))
          nil))))

;;; WebSub

(defn ping-hub!
  "Notify the :websub-hub of `conf` that the feed under its :url has updated;
  returns the hub's answer, a map of its :status (superfeedr answers 204) or
  of the :error, or nil without a hub."
  [{:keys [websub-hub url] :as conf}]
  (when websub-hub
    (let [feed-url (str url shared/feed-path)
          [{:keys [status error] :as answer}]
          (websub/publish! [websub-hub] feed-url {:send http/send!})]
      (tel/log! {:level (if error :warn :info)
                 :id    ::hub-pinged
                 :data  (assoc answer :feed feed-url)
                 :msg   (str "WebSub hub pinged for " feed-url ": "
                             (or status (:reason error)))})
      answer)))

;;; Publishing

(def notify-delay
  "Seconds to wait after a post sync before notifying the outside world;
  collapses the multiple watcher events emitted per file save."
  10)

(defonce scheduler
  (delay (Executors/newSingleThreadScheduledExecutor)))

(defonce queued
  ;; #{[year slug] ...} of synced posts awaiting notification.
  (atom #{}))

(defn- notify!
  "Send Webmentions for every queued post in `db` and ping the WebSub hub."
  [db conf]
  (let [[posts _] (reset-vals! queued #{})]
    (when (seq posts)
      (run! (fn [[year slug]] (send-webmentions! db conf year slug)) posts)
      (ping-hub! conf))))

(defn schedule-notify!
  "Schedule outgoing notifications for a synced `post` (Webmentions and a
  WebSub hub ping), debounced by `notify-delay`; meant for use as the on-sync
  hook of db/watch!."
  [db conf {:keys [year slug] :as post}]
  (let [[prior _] (swap-vals! queued conj [year slug])]
    ;; Only the first post in a window arms the timer; later ones ride it.
    (when (empty? prior)
      (.schedule ^ScheduledExecutorService @scheduler
                 ^Runnable #(notify! db conf)
                 ^long notify-delay TimeUnit/SECONDS))))

(comment
  (require '[blog.grays.web.service :as service])
  (def conf service/dev-conf)

  ;; Which external URLs does a post link to?
  (external-links (:url conf)
                  (db/find-post db/db "2020" "clojure-the-lisp-that-wants-to-spread"))

  ;; Endpoint discovery against the webmention.rocks test suite.
  (webmention/discover! "https://webmention.rocks/test/1" wm-opts)      ; <link> in body
  (webmention/discover! "https://webmention.rocks/test/8" wm-opts)      ; Link header
  (webmention/discover! "https://webmention.rocks/test/15" wm-opts)     ; empty href = page
  (webmention/discover! "https://webmention.rocks/test/23/page" wm-opts) ; redirect

  ;; The real thing (only meaningful for deployed posts).
  (send-webmentions! db/db conf "2026" "some-post")

  ;; Tell the WebSub hub that the feed has new content.
  (ping-hub! conf)

  ;; Receiving: simulate an incoming mention, then read it back once the watcher
  ;; has synced the file it was written to.
  (receive-mention! db/db conf
                    "https://example.com/some-page"
                    "https://simon.grays.blog/posts/2020/clojure-the-lisp-that-wants-to-spread")
  (indieweb/mentions (:indieweb-dir conf) "/posts/2020/clojure-the-lisp-that-wants-to-spread")
  (db/verified-mentions db/db "/posts/2020/clojure-the-lisp-that-wants-to-spread")

  ;; Moderation: hide a mention and refuse future re-sends of it.
  (block-mention! conf "https://example.com/some-page"
                  "/posts/2020/clojure-the-lisp-that-wants-to-spread")
  #_.)
