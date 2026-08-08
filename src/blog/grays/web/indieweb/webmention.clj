(ns blog.grays.web.indieweb.webmention
  "Functions for sending and receiving Webmentions
  (https://www.w3.org/TR/webmention/).

  Sending is driven by the post watcher via `schedule-notify!` where conf has
  :send-webmentions?, and by REPL otherwise; source URLs must be publicly
  reachable, so it only makes sense for deployed posts. Receiving stores
  incoming mentions as :pending and verifies them against their source
  asynchronously; only :verified mentions are ever displayed. The
  :webmention-endpoint conf key decides whether this native endpoint or a
  hosted one (webmention.io) is advertised to other sites.

  Everything learned here is written to the :indieweb-dir as files (see the
  indieweb namespace) and the watcher syncs it into the db. Reads therefore go
  to the db and writes to the files, never the other way round.

  The pages we fetch are read by the html sub-namespace, which hands back plain
  data. The WebSub hub ping lives here too, since it shares the HTTP plumbing."
  (:require [clojure.string :as str]
            [taoensso.telemere :as tel]
            [dk.cst.hiccup-tools.hiccup :as hiccup]
            [dk.cst.hiccup-tools.match :as match]
            [blog.grays.web.db :as db]
            [blog.grays.web.http :as http]
            [blog.grays.web.shared :as shared]
            [blog.grays.web.indieweb :as indieweb]
            [blog.grays.web.indieweb.webmention.html :as html])
  (:import [java.net URI]
           [java.time Instant]
           [java.util.concurrent Executors TimeUnit]))

(defonce ^:private fetcher
  ;; A small fixed pool doubles as backpressure against verification floods.
  (delay (Executors/newFixedThreadPool 2)))

(defn- fetch-page
  "Fetch the page at `url` and parse its HTML; nil when it cannot be read."
  [url]
  (try
    (let [{:keys [body] :as response} (http/GET url)]
      (when (http/ok? response)
        (html/parse body (:url response))))
    (catch Exception e
      (tel/log! {:level :info
                 :id    ::fetch-error
                 :data  {:url url}
                 :msg   (str "Could not fetch " url ": " (ex-message e))})
      nil)))

(defn- status-level
  "The log level appropriate for an HTTP `status`: 2xx is expected."
  [status]
  (if (<= 200 status 299) :info :warn))

;;; Sending

(defn external-links
  "Absolute http(s) URLs linked from the :hiccup of `post`, excluding those
  under our own `url`."
  [url {:keys [hiccup] :as post}]
  (->> (hiccup/search hiccup {:links (match/match :a {:href true})})
       (:links)
       (map (comp :href second))
       (filter #(str/starts-with? % "http"))
       (remove #(str/starts-with? % url))
       (distinct)))

(defn header-links
  "Parse a comma-separated Link `header` value into [href rel-set] pairs."
  [header]
  (for [part (str/split header #",\s*(?=<)")
        :let [[_ href] (re-find #"<([^>]*)>" part)
              [_ rel]  (re-find #"rel\s*=\s*\"?([^\";]*)\"?" part)]
        :when href]
    [href (set (str/split (or rel "") #"\s+"))]))

(defn- header-endpoint
  "The rel=webmention href in the Link header(s) of `response`, if any."
  [{:keys [headers] :as response}]
  (->> (get headers "link")
       (mapcat header-links)
       (some (fn [[href rels]]
               (when (rels "webmention") href)))))

(defn discover-endpoint
  "The Webmention endpoint advertised by the page at `target`, if any.

  Checks the Link header first, then the first <link>/<a> rel~=webmention in
  the body; relative hrefs resolve against the final, post-redirect URL. An
  empty href means the page itself (Java's URI.resolve deviates from RFC 3986
  here, so it is special-cased)."
  [target]
  (let [{:keys [url body] :as response} (http/GET target)]
    (when-let [href (or (header-endpoint response)
                        (html/endpoint-href (html/parse body url)))]
      (let [endpoint (if (str/blank? href)
                       url
                       (str (.resolve (URI. url) ^String href)))]
        (when (re-find #"^https?://" endpoint)
          endpoint)))))

(defn send-webmention!
  "POST a `source`/`target` Webmention to `endpoint`; returns the response
  status (2xx means accepted)."
  [endpoint source target]
  (let [{:keys [status]} (http/POST-form endpoint {:source source
                                                   :target target})]
    (tel/log! {:level (status-level status)
               :id    ::sent
               :data  {:endpoint endpoint :source source :target target :status status}
               :msg   (str "Webmention " source " -> " target ": " status)})
    status))

(defn send-webmentions!
  "Discover endpoints and send Webmentions for every external link in the post
  at `year`/`slug` in `conn`, using its permalink under the :url of `conf` as
  the source; the URLs the post responds to (see db/response-verb-attrs), the
  :bridgy-fed bridge, and every previously notified target are also targets,
  which is how post updates and deletions propagate per the spec.

  Deliveries are recorded in the :indieweb-dir. Returns a map of target -> status
  for REPL inspection, where a status is an HTTP status code, :no-endpoint, or
  :error (logged under ::send-error)."
  [conn {:keys [url indieweb-dir bridgy-fed] :as conf} year slug]
  (let [path    (shared/post-href year slug)
        source  (str url path)
        post    (db/get-post conn year slug)            ; nil once a post is deleted
        targets (distinct (concat (db/get-delivery-targets conn path)
                                  (keep #(get post %) db/response-verb-attrs)
                                  ;; A deleted post is never newly announced to
                                  ;; the bridge; it is re-sent from the delivery
                                  ;; record above, which is what tells Bridgy Fed
                                  ;; to withdraw the federated copy.
                                  (when (and post bridgy-fed) [bridgy-fed])
                                  (some->> post (external-links url))))]
    (into {}
          (for [target targets]
            [target (try
                      (if-let [endpoint (discover-endpoint target)]
                        (let [status (send-webmention! endpoint source target)]
                          (indieweb/put-delivery! indieweb-dir path target
                                                  {:at     (str (Instant/now))
                                                   :status (str status)})
                          status)
                        :no-endpoint)
                      (catch Exception e
                        (tel/error! {:id   ::send-error
                                     :data {:source source :target target}}
                                    e)
                        :error))]))))

;;; Receiving

(defn- target-path
  "The local permalink path of the absolute `target` URL under our `url`,
  provided it identifies an existing post in `conn`."
  [conn url target]
  (when (str/starts-with? (str target) url)
    (let [path (subs (str target) (count url))]
      (when (db/post-at-path conn path)
        path))))

(defn- mention-kind
  "The kind of mention that the parsed `entry` makes of `target`."
  [entry target]
  (or (some (fn [kind]
              (when (contains? (get entry kind) target) kind))
            (keys html/kind->class))
      :mention))

(def excerpt-length
  "How much of a reply we keep: enough to read at a glance, not so much that our
  files become a mirror of somebody else's post."
  280)

(defn- cache-avatar!
  "Fetch and locally cache the avatar at `photo-url`, returning its served path,
  or nil when there is no url or the fetch fails. Wrapped so that a missing
  avatar never fails the verification it is part of."
  [{:keys [indieweb-dir] :as conf} photo-url]
  (when photo-url
    (try
      (when-let [{:keys [bytes ext]} (http/GET-image photo-url)]
        (indieweb/put-avatar! indieweb-dir photo-url ext bytes))
      (catch Exception e
        (tel/log! {:level :info
                   :id    ::avatar-error
                   :data  {:url photo-url}
                   :msg   (str "Could not cache avatar " photo-url ": " (ex-message e))})
        nil))))

(defn author-attrs
  "The comment author attributes gleaned from the h-card on the homepage at
  `me`: the URL itself, plus whatever name and photo (cached under `conf`'s
  :indieweb-dir) the page marks up."
  [conf me]
  (let [{:keys [name photo]} (some-> (fetch-page me) (html/card))]
    {:author-url         me
     :author-name        name
     :author-photo       photo
     :author-photo-cache (cache-avatar! conf photo)}))

(defn verify-mention!
  "Fetch `source` and settle the status of its mention of the post at the local
  `path`: :verified when the source links to that post's permalink under the
  :url of `conf`, with url, author, kind, and publication details parsed from
  its microformats, and :failed otherwise, including when a previously verified
  link has disappeared (the spec's deletion mechanism)."
  [{:keys [url indieweb-dir] :as conf} source path]
  (let [target  (str url path)
        entry   (some-> (fetch-page source) (html/entry))
        photo   (get-in entry [:author :photo])
        kind    (mention-kind entry target)
        mention (merge {:status   :failed
                        :received (str (Instant/now))}
                       (when (contains? (:links entry) target)
                         (shared/compact
                           {:status             :verified
                            :kind               kind
                            :url                (:url entry)
                            :author-name        (get-in entry [:author :name])
                            :author-url         (get-in entry [:author :url])
                            :author-photo       photo
                            ;; The local copy we actually render; nil (and so
                            ;; dropped) when there is no photo or the fetch fails.
                            :author-photo-cache (cache-avatar! conf photo)
                            :published          (:published entry)
                            ;; Only a reply keeps its content: a like has none,
                            ;; and the e-content of a plain mention is somebody
                            ;; else's entire post.
                            :content            (when (= :reply kind)
                                                  (shared/truncate excerpt-length
                                                                   (:content entry)))})))]
    (indieweb/put-mention! indieweb-dir path source mention)
    (tel/log! {:level :info
               :id    ::verified
               :data  (assoc mention :source source :target path)
               :msg   (str "Webmention " source " -> " path ": "
                           (name (:status mention)))})
    (:status mention)))

(defn cache-avatars!
  "Backfill the avatar cache: for every verified mention in the :indieweb-dir of
  `conf` that has an :author-photo but no local copy, fetch and cache it, then
  rewrite the mention file so the watcher re-syncs it. New mentions cache their
  avatar at verification time; this is for the ones that predate the cache."
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
  "Handle an incoming Webmention of `target` by `source`: validate the request
  synchronously per the spec, store the mention as :pending, and hand source
  verification to the fetcher pool. Returns the local post path when accepted
  and nil otherwise (=> HTTP 400).

  Re-received mentions are re-verified, which is the spec's mechanism for
  updates and deletions, except those previously :blocked by moderation."
  [conn {:keys [url indieweb-dir] :as conf} source target]
  (when (and (http/valid-url? source)
             (http/valid-url? target)
             (not= source target))
    (when-let [path (target-path conn url target)]
      (when (not= :blocked (get-in (indieweb/mentions indieweb-dir path) [source :status]))
        (indieweb/put-mention! indieweb-dir path source
                               {:status   :pending
                                :received (str (Instant/now))})
        (.submit @fetcher ^Runnable #(verify-mention! conf source path))
        path))))

(defn block-mention!
  "Moderation: mark the mention of the local `path` by `source` as :blocked,
  hiding it and refusing future re-sends. Deleting the entry from the IndieWeb
  file unblocks it, as does editing the file by hand, which is all this does."
  [{:keys [indieweb-dir] :as conf} source path]
  (indieweb/put-mention! indieweb-dir path source {:status :blocked}))

;;; Reply contexts

(defn fetch-context!
  "Fetch the reply context (title/author) of `url` and cache it in the
  :indieweb-dir of `conf`. Failures are cached too, as an entry without
  title/author, so that a dead link is not retried on every render. Call this
  directly to retry one anyway."
  [{:keys [indieweb-dir] :as conf} url]
  (let [entry (some-> (fetch-page url) (html/entry))]
    (indieweb/put-context! indieweb-dir url
                           (merge {:fetched (str (Instant/now))}
                                  (shared/compact
                                    {:title  (:title entry)
                                     :author (get-in entry [:author :name])})))))

(defonce ^:private attempted
  ;; The reply-context URLs already fetched this session. A fetch takes seconds
  ;; and only reaches the db once the watcher has synced the file it writes, so
  ;; without this every render in between would schedule another one.
  (atom #{}))

(defn reply-context
  "The cached reply context of `url` in `conn`; a cache miss returns nil and
  schedules an asynchronous fetch, at most one per URL, so that a subsequent
  render gets the context."
  [conn conf url]
  (when url
    (or (db/get-context conn url)
        (let [[prior _] (swap-vals! attempted conj url)]
          (when-not (prior url)
            (.submit @fetcher ^Runnable #(fetch-context! conf url)))
          nil))))

;;; WebSub

(defn ping-hub!
  "Notify the :websub-hub of `conf` that the feed under its :url has updated;
  returns the response status (superfeedr answers 204) or nil without a hub."
  [{:keys [websub-hub url] :as conf}]
  (when websub-hub
    (let [feed-url (str url shared/feed-path)
          {:keys [status]} (http/POST-form websub-hub {"hub.mode" "publish"
                                                       "hub.url"  feed-url})]
      (tel/log! {:level (status-level status)
                 :id    ::hub-pinged
                 :data  {:hub websub-hub :feed feed-url :status status}
                 :msg   (str "WebSub hub pinged for " feed-url ": " status)})
      status)))

;;; Publishing

(def ^:private notify-delay
  "Seconds to wait after a post sync before notifying the outside world;
  collapses the multiple watcher events emitted per file save."
  10)

(defonce ^:private scheduler
  (delay (Executors/newSingleThreadScheduledExecutor)))

(defonce ^:private queued
  ;; #{[year slug] ...} of synced posts awaiting notification.
  (atom #{}))

(defn- notify!
  "Send Webmentions for every queued post in `conn` and ping the WebSub hub."
  [conn conf]
  (let [[posts _] (reset-vals! queued #{})]
    (when (seq posts)
      (run! (fn [[year slug]] (send-webmentions! conn conf year slug)) posts)
      (ping-hub! conf))))

(defn schedule-notify!
  "Schedule outgoing notifications for a synced `post` (Webmentions and a
  WebSub hub ping), debounced by `notify-delay`; meant for use as the on-sync
  hook of db/watch!."
  [conn conf {:keys [year slug] :as post}]
  (let [[prior _] (swap-vals! queued conj [year slug])]
    (when (empty? prior)
      (.schedule ^java.util.concurrent.ScheduledExecutorService @scheduler
                 ^Runnable #(notify! conn conf)
                 ^long notify-delay TimeUnit/SECONDS))))

(comment
  (require '[blog.grays.web.service :as service])
  (def conf service/dev-conf)
  (def conn (db/get-conn (:db-dir conf)))

  ;; Which external URLs does a post link to?
  (external-links (:url conf)
                  (db/get-post conn "2020" "clojure-the-lisp-that-wants-to-spread"))

  ;; Endpoint discovery against the webmention.rocks test suite.
  (discover-endpoint "https://webmention.rocks/test/1")      ; <link> in body
  (discover-endpoint "https://webmention.rocks/test/8")      ; Link header
  (discover-endpoint "https://webmention.rocks/test/15")     ; empty href = page
  (discover-endpoint "https://webmention.rocks/test/23/page") ; redirect

  ;; The real thing (only meaningful for deployed posts).
  (send-webmentions! conn conf "2026" "some-post")

  ;; Tell the WebSub hub that the feed has new content.
  (ping-hub! conf)

  ;; Receiving: simulate an incoming mention, then read it back once the watcher
  ;; has synced the file it was written to.
  (receive-mention! conn conf
                    "https://example.com/some-page"
                    "https://simon.grays.blog/posts/2020/clojure-the-lisp-that-wants-to-spread")
  (indieweb/mentions (:indieweb-dir conf) "/posts/2020/clojure-the-lisp-that-wants-to-spread")
  (db/get-mentions conn "/posts/2020/clojure-the-lisp-that-wants-to-spread")

  ;; Moderation: hide a mention and refuse future re-sends of it.
  (block-mention! conf "https://example.com/some-page"
                  "/posts/2020/clojure-the-lisp-that-wants-to-spread")
  #_.)
