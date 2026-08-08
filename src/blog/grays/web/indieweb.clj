(ns blog.grays.web.indieweb
  "The IndieWeb data we cannot regenerate: the Webmentions we have received, the
  ones we have delivered, the reply contexts we have fetched, and the comments
  visitors have signed in to write.

  Posts are files, and the content db is merely derived from them. This
  namespace extends that arrangement to everything else, so that the db is
  derived in its entirety and can be wiped and rebuilt at will (see
  `db/rebuild!`), so schema changes stop being migrations.

  Nothing here is written by hand, but everything here *can* be, which is what
  moderation amounts to: set a mention's :status to :blocked in your editor and
  the watcher hides it.

  Entries follow the file conventions of the store namespace, keyed by the
  remote URL:

    mentions/2020/some-post.edn    {source-url {:status .. :kind .. ..}}
    deliveries/2020/some-post.edn  {target-url {:at .. :status ..}}
    contexts.edn                   {url {:title .. :author ..}}
    comments/2020/some-post.edn    {id {:status .. :content ..}}

  Comments are the exception to the remote key, having no remote URL; the
  comments namespace owns their half of the directory and this one aggregates
  it. Keys are bare on disk and namespaced on the way into the db
  (:mention/source and so on), the same way post frontmatter is."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [blog.grays.web.indieweb.comments :as comments]
            [blog.grays.web.indieweb.store :as store])
  (:import [java.io File]
           [java.security MessageDigest]))

(defn- contexts-file
  [dir]
  (store/data-file dir "contexts.edn"))

(defn- mentions-dir
  [dir]
  (store/data-file dir "mentions"))

(defn- deliveries-dir
  [dir]
  (store/data-file dir "deliveries"))

(def ^:private avatars-subdir "avatars")

(defn- sha256-hex
  "The SHA-256 of `s` as a lowercase hex string."
  [s]
  (->> (.digest (MessageDigest/getInstance "SHA-256") (.getBytes ^String s "UTF-8"))
       (map #(format "%02x" (bit-and % 0xff)))
       (str/join)))

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

;;; Reading

(defn mentions
  "The mentions of the post at the permalink `path`, keyed by source URL."
  [dir path]
  (store/read-edn (store/entry-file (mentions-dir dir) path)))

(defn all-mentions
  "Every mention across dir's mention files, as [path source mention] triples.
  The whole-directory counterpart to `mentions`, which reads a single post's."
  [dir]
  (for [file (store/edn-files (mentions-dir dir))
        :let [path (store/entry-path (mentions-dir dir) file)]
        [source mention] (store/read-edn file)]
    [path source mention]))

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
