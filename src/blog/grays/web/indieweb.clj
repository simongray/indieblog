(ns blog.grays.web.indieweb
  "The IndieWeb data we cannot regenerate: the Webmentions we have received, the
  ones we have delivered, and the reply contexts we have fetched.

  Posts are files, and the content db is merely derived from them. This
  namespace extends that arrangement to everything else, so that the db is
  derived in its entirety and can be wiped and rebuilt at will (see
  `db/rebuild!`) — schema changes stop being migrations.

  Nothing here is written by hand, but everything here *can* be, which is what
  moderation amounts to: set a mention's :status to :blocked in your editor and
  the watcher hides it.

  Entries are EDN maps keyed by the remote URL, under a filename carrying the
  local permalink path:

    mentions/2020/some-post.edn    {source-url {:status .. :kind .. ..}}
    deliveries/2020/some-post.edn  {target-url {:at .. :status ..}}
    contexts.edn                   {url {:title .. :author ..}}

  Keys are bare on disk and namespaced on the way into the db (:mention/source
  and so on), the same way post frontmatter is. That the local half of a
  Webmention is the filename and the remote half the key is why neither needs
  an identity attribute: the file *is* the index."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str])
  (:import [java.io File]
           [java.nio.file CopyOption Files StandardCopyOption]))

(def ^:private lock
  ;; Every write is a read-modify-write of a whole file, so they are serialised;
  ;; the fetcher pool would otherwise lose updates to contexts.edn.
  (Object.))

(defn- read-edn
  "The EDN map in `file`, or nil when it does not exist."
  [^File file]
  (when (.exists file)
    (edn/read-string (slurp file))))

(defn- write-edn!
  "Write `m` to `file` as pretty-printed EDN, atomically: the watcher must never
  read a half-written file, and the file must stay pleasant to hand-edit."
  [^File file m]
  (io/make-parents file)
  ;; NB: a .tmp suffix, so that the temp file is not itself read or watched.
  (let [tmp (File/createTempFile "indieweb" ".tmp" (.getParentFile file))]
    (spit tmp (with-out-str (pp/pprint m)))
    (Files/move (.toPath tmp)
                (.toPath file)
                (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                        StandardCopyOption/REPLACE_EXISTING]))))

(defn- update-file!
  "Apply `f` to the EDN map in `file` and write the result back."
  [file f]
  (locking lock
    (write-edn! file (f (or (read-edn file) {})))))

(defn- data-file
  "The file at `parts` under `dir`.

  Asserts the dir, since io/file quietly treats a nil parent as the working
  directory — which would scatter our data wherever the JVM happens to run."
  [dir & parts]
  (assert dir "conf has no :indieweb-dir")
  (apply io/file dir parts))

(defn- entry-file
  "The file of `kind` holding the entries of the post at the permalink `path`."
  [dir kind path]
  (data-file dir (name kind) (str (subs path (count "/posts/")) ".edn")))

(defn- entry-path
  "The permalink path of the post whose `kind` entries `file` holds; the inverse
  of `entry-file`."
  [dir kind ^File file]
  (let [root (.toPath (data-file dir (name kind)))
        rel  (str (.relativize root (.toPath file)))]
    (str "/posts/" (str/replace rel #"\.edn$" ""))))

(defn- contexts-file
  [dir]
  (data-file dir "contexts.edn"))

(defn ensure-dirs!
  "Create `dir` and its subdirectories, so that they can be watched."
  [dir]
  (run! #(.mkdirs ^File (data-file dir %)) ["mentions" "deliveries"]))

;;; Writing

(defn put-mention!
  "Record the `mention` of the post at the permalink `path` by `source`."
  [dir path source mention]
  (update-file! (entry-file dir :mentions path) #(assoc % source mention)))

(defn put-delivery!
  "Record the `delivery` of a Webmention to `target` for the post at the
  permalink `path`."
  [dir path target delivery]
  (update-file! (entry-file dir :deliveries path) #(assoc % target delivery)))

(defn put-context!
  "Cache the reply `context` fetched from `url`."
  [dir url context]
  (update-file! (contexts-file dir) #(assoc % url context)))

;;; Reading

(defn mentions
  "The mentions of the post at the permalink `path`, keyed by source URL."
  [dir path]
  (read-edn (entry-file dir :mentions path)))

(defn- edn-files
  [dir]
  (->> (file-seq (io/file dir))
       (filter #(str/ends-with? (.getName ^File %) ".edn"))))

(defn- qualify
  "The map `m` with its keys moved into the `ns` namespace."
  [ns m]
  (update-keys m #(keyword (name ns) (name %))))

(defn entities
  "Every mention, delivery and reply context in `dir`, as db entity maps."
  [dir]
  (concat
    (for [file (edn-files (data-file dir "mentions"))
          :let [path (entry-path dir :mentions file)]
          [source mention] (read-edn file)]
      (assoc (qualify :mention mention)
        :mention/source source
        :mention/target path))
    (for [file (edn-files (data-file dir "deliveries"))
          :let [path (entry-path dir :deliveries file)]
          [target delivery] (read-edn file)]
      (assoc (qualify :delivery delivery)
        :delivery/source path
        :delivery/target target))
    (for [[url context] (read-edn (contexts-file dir))]
      (assoc (qualify :context context)
        :context/url url))))

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
