(ns blog.grays.web.store
  "EDN-file persistence for the data that is synced into the db but cannot be
  regenerated from the posts: the shared conventions of the indieweb namespace
  and (soon) native comments.

  Entries are EDN maps keyed by a remote key (a URL, an id), in a file whose
  name carries the local permalink path:

    <dir>/<kind>/2020/some-post.edn    {remote-key {...}}

  The local half in the filename and the remote half in the key is why neither
  needs an identity attribute in the db: the file *is* the index. Every file
  stays pleasant to hand-edit, which is what moderation amounts to, and writes
  are serialised and atomic (temp file + ATOMIC_MOVE), so the file watchers can
  never read a half-written file."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str])
  (:import [java.io File]
           [java.nio.file CopyOption Files StandardCopyOption]))

(def ^:private lock
  ;; Every write is a read-modify-write of a whole file, so they are serialised;
  ;; concurrent writers (e.g. the webmention fetcher pool) would otherwise lose
  ;; updates.
  (Object.))

(defn read-edn
  "The EDN map in `file`, or nil when it does not exist."
  [^File file]
  (when (.exists file)
    (edn/read-string (slurp file))))

(defn atomic-write!
  "Write to `file` atomically: `write-tmp!` is handed a temp file in the same
  directory to fill, and that file is then moved into place. The watcher must
  never read a half-written file."
  [^File file write-tmp!]
  (io/make-parents file)
  ;; NB: a .tmp suffix, so that the temp file is not itself read or watched.
  (let [tmp (File/createTempFile "store" ".tmp" (.getParentFile file))]
    (write-tmp! tmp)
    (Files/move (.toPath tmp)
                (.toPath file)
                (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                        StandardCopyOption/REPLACE_EXISTING]))))

(defn- write-edn!
  "Write `m` to `file` as pretty-printed EDN, atomically; the file must stay
  pleasant to hand-edit."
  [^File file m]
  (atomic-write! file #(spit % (with-out-str (pp/pprint m)))))

(defn update-file!
  "Apply `f` to the EDN map in `file` and write the result back."
  [file f]
  (locking lock
    (write-edn! file (f (or (read-edn file) {})))))

(defn data-file
  "The file at `parts` under `dir`.

  Asserts the dir, since io/file quietly treats a nil parent as the working
  directory — which would scatter our data wherever the JVM happens to run."
  [dir & parts]
  (assert dir "no data dir given")
  (apply io/file dir parts))

(defn entry-file
  "The file holding the entries of the post at the permalink `path`, under
  `dir`."
  [dir path]
  (data-file dir (str (subs path (count "/posts/")) ".edn")))

(defn entry-path
  "The permalink path of the post whose entries `file` holds, under `dir`; the
  inverse of `entry-file`."
  [dir ^File file]
  (let [root (.toPath (data-file dir))
        rel  (str (.relativize root (.toPath file)))]
    (str "/posts/" (str/replace rel #"\.edn$" ""))))

(defn edn-files
  "Every .edn file under `dir`."
  [dir]
  (->> (file-seq (io/file dir))
       (filter #(str/ends-with? (.getName ^File %) ".edn"))))

(defn qualify
  "The map `m` with its keys moved into the `ns` namespace."
  [ns m]
  (update-keys m #(keyword (name ns) (name %))))
