(ns blog.grays.web.indieweb
  "The IndieWeb data we cannot regenerate: received and delivered
  Webmentions, fetched reply contexts, and the comments visitors have signed
  in to write.

  Like posts, these are files, and the content db is derived from them in
  its entirety (see the db namespace). Nothing here is written by hand, but
  everything *can* be: moderation is setting a mention's :status to :blocked
  in your editor. Entries follow the file conventions of the store
  namespace, keyed by the remote URL (comments, having none, by an id):

    mentions/2020/some-post.edn    {source-url {:status .. :kind .. ..}}
    deliveries/2020/some-post.edn  {target-url {:at .. :status ..}}
    contexts.edn                   {url {:title .. :author ..}}
    comments/2020/some-post.edn    {id {:status .. :content ..}}
    tokens.edn                     {token-hash {:type .. :me .. :scope ..}}
    oauth-clients.edn              {instance-origin {:client-id .. ..}}

  The last two belong to the auth namespace and are *not* synced into the
  db: tokens.edn is the store of the tokens our IndieAuth server issued,
  keyed by hash so the file leaks nothing; oauth-clients.edn the OAuth apps
  we registered as at Mastodon instances. Keys are bare on disk and
  namespaced on the way into the db."
  (:require [clojure.java.io :as io]
            [dk.simongray.wary-fetch.secret :as secret]
            [dk.simongray.webcrypto.bytes :as bytes]
            [dk.simongray.webcrypto.hash :as hash]
            [blog.grays.web.indieweb.comments :as comments]
            [blog.grays.web.indieweb.store :as store])
  (:import [java.io File]))

(defn- contexts-file
  [dir]
  (store/data-file dir "contexts.edn"))

(defn- mentions-dir
  [dir]
  (store/data-file dir "mentions"))

(defn- deliveries-dir
  [dir]
  (store/data-file dir "deliveries"))

(def avatars-subdir "avatars")

(defn- sha256-hex
  "The SHA-256 of `s` as a lowercase hex string."
  [s]
  (bytes/hex (hash/digest :sha-256 (bytes/encode s))))

(defn- avatar-file
  "The cache file for the avatar fetched from `url`, extension `ext`, under dir's
  avatars subdir. Named by a hash of the URL: stable, filesystem-safe whatever
  the URL looked like, and unique enough at this scale."
  [dir url ext]
  (store/data-file dir avatars-subdir (str (sha256-hex url) "." ext)))

(defn avatar-path
  "The served path of an avatar cache `file`, under the /avatars prefix that
  service.clj maps onto the avatars subdir."
  [^File file]
  (str "/" avatars-subdir "/" (.getName file)))

(defn ensure-dirs!
  "Create `dir` and its subdirectories, so they exist to be watched (the EDN
  ones) and served (avatars)."
  [dir]
  (run! (fn [^File d] (.mkdirs d))
        [(mentions-dir dir) (deliveries-dir dir) (store/data-file dir avatars-subdir)])
  (comments/ensure-dir! dir))

;;; Writing

(defn put-mention!
  "Record the `mention` of the post at the permalink `path` by `source`."
  [dir path source mention]
  (store/update-file! (store/entry-file (mentions-dir dir) path) #(assoc % source mention)))

(defn put-delivery!
  "Record the `delivery` of a Webmention to `target` for the post at the
  permalink `path`."
  [dir path target delivery]
  (store/update-file! (store/entry-file (deliveries-dir dir) path) #(assoc % target delivery)))

(defn put-context!
  "Cache the reply `context` fetched from `url`."
  [dir url context]
  (store/update-file! (contexts-file dir) #(assoc % url context)))

(defn put-avatar!
  "Cache image `bytes` (extension `ext`) fetched from `url` under dir's avatars
  subdir, and return the served path of the file written. Distinct URLs get
  distinct files, so unlike the EDN writers this needs no lock."
  [dir url ext bytes]
  (let [file (avatar-file dir url ext)]
    (store/atomic-write! file #(with-open [out (io/output-stream %)]
                                 (.write out ^bytes bytes)))
    (avatar-path file)))

(defn- tokens-file
  [dir]
  (store/data-file dir "tokens.edn"))

(defn token-store
  "The store of tokens of indieauth-clj's server in tokens.edn under `dir`:
  a map of :put!, :get, :take! and :delete!, as server/memory-store has it.

  The server keys every entry by a hash, so the file holds no token, and an
  entry past its :expires goes at the next write."
  [dir]
  (let [file    (tokens-file dir)
        live    (fn [m]
                  (into {} (remove (fn [[_ v]]
                                     (some-> (:expires v) (inst-ms)
                                             (<= (System/currentTimeMillis)))))
                        m))
        update! #(store/update-file! file (comp live %))]
    {:put!    (fn [k v] (update! #(assoc % k v)) nil)
     :get     (fn [k] (get (store/read-edn file) k))
     ;; Under the store's lock, so two takes never both get the entry.
     :take!   (fn [k]
                (let [taken (volatile! nil)]
                  (update! #(do (vreset! taken (get % k)) (dissoc % k)))
                  @taken))
     :delete! (fn [k] (update! #(dissoc % k)) nil)}))

(defn- oauth-clients-file
  [dir]
  (store/data-file dir "oauth-clients.edn"))

(defn registration-store
  "The store of relmeauth-clj's registrations at Mastodon instances in
  oauth-clients.edn under `dir`: a map of :get and :put!. The client secret
  is written out as plain text, like the rest of our data."
  [dir]
  (let [file (oauth-clients-file dir)]
    {:get  (fn [instance]
             (some-> (get (store/read-edn file) instance)
                     (update :client-secret secret/hide)))
     :put! (fn [instance registration]
             (store/update-file! file #(assoc % instance (update registration :client-secret
                                                                 secret/reveal))))}))

;;; Reading

(defn mentions
  "The mentions of the post at the permalink `path`, keyed by source URL."
  [dir path]
  (store/read-edn (store/entry-file (mentions-dir dir) path)))

(defn all-mentions
  "Every mention across dir's mention files, as [path source mention] triples.
  The whole-directory counterpart to `mentions`, which reads a single post's."
  [dir]
  (let [root (mentions-dir dir)]
    (for [file (store/edn-files root)
          :let [path (store/entry-path root file)]
          [source mention] (store/read-edn file)]
      [path source mention])))

(defn entities
  "Every mention, delivery, reply context and comment in `dir`, as db entity
  maps."
  [dir]
  (concat
    (for [[path source mention] (all-mentions dir)]
      (assoc (store/qualify :mention mention)
        :mention/source source
        :mention/target path))
    (for [file (store/edn-files (deliveries-dir dir))
          :let [path (store/entry-path (deliveries-dir dir) file)]
          [target delivery] (store/read-edn file)]
      (assoc (store/qualify :delivery delivery)
        :delivery/source path
        :delivery/target target))
    (for [[url context] (store/read-edn (contexts-file dir))]
      (assoc (store/qualify :context context)
        :context/url url))
    (comments/entities dir)))

(comment
  (require '[blog.grays.web.service :as service])
  (def dir (:indieweb-dir service/dev-conf))

  (ensure-dirs! dir)

  (put-mention! dir "/posts/2020/some-post" "https://example.com/a-page"
                {:status      :verified
                 :kind        :reply
                 :received    "2026-07-14T09:12:03Z"
                 :author-name "Jane Doe"})

  (mentions dir "/posts/2020/some-post")
  (entities dir)
  #_.)
