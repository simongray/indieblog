(ns blog.grays.web.content
  "Functions for creating content to populate the database with."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [dk.cst.hiccup-tools.elem :as elem]
            [nextjournal.markdown :as md]
            [nextjournal.markdown.transform :refer [->hiccup]]
            [sluj.core :refer [sluj]]
            [blog.grays.web.shared :as shared])
  (:import [java.io File]))

(def img-ext
  #{"jpg" "jpeg" "gif" "png" "svg"})

(def supported-ext
  (conj img-ext "md"))

(defn file-ext
  "Get the file extension for the file at the given `path`."
  [path]
  (last (str/split path #"\.")))

(def canonical-path-xf
  (comp
    (remove #(.isDirectory ^File %))
    (map #(.getCanonicalPath ^File %))))

(defn recursive-search
  "Return all files recursively starting from a top `dir`."
  [^File dir]
  (into [] canonical-path-xf (file-seq dir)))

(defn by-extension
  "Load a `dir` as a map of file extensions to file paths."
  [^File dir]
  (group-by file-ext (recursive-search dir)))

(defn ext-filter
  "Filter files in `dir` by file `ext`."
  [ext dir]
  (let [ext->files (by-extension (io/file dir))]
    (if (string? ext)
      (get ext->files ext)
      (mapcat second (select-keys ext->files ext)))))

(def yaml-frontmatter
  #"^---\n((?:\s|.)+?)---")

(defn yaml->map
  "Convert `yaml` kvs to a Clojure map."
  [yaml]
  (into {} (for [line (str/split yaml #"\n")]
             (let [[k v] (str/split (str/trim line) #":\s")]
               [(keyword k) v]))))

(defn derive-kv
  "Note derivation of `v` for `k` in `entity`."
  [{:keys [derived] :as entity} k v]
  (when v
    (assoc entity
      k v
      :derived (if derived
                 (conj derived k)
                 #{k}))))

(defn hiccup-title
  "Derive a plain-text title string from the first <h1> in `hiccup`, if any.

  The <h1> content is a seq of inline nodes (and may contain inline markup such
  as <code> or <em>), so it is flattened to a single string via
  shared/stringify. Returning a string keeps :title a proper :db.type/string."
  [hiccup]
  (let [[tag _ content] (some-> (elem/children hiccup)
                                (first)
                                (elem/parts))]
    (when (= :h1 tag)
      (shared/stringify (into [:h1] (if (sequential? content)
                                      content
                                      [content]))))))

(defn expand-post
  "Derive additional metadata for a `post` entity."
  [{:keys [date file title slug content hiccup year location language] :as post}]
  (let [derived-title (hiccup-title hiccup)]
    (cond-> post
      (not title) (derive-kv :title derived-title)
      (not slug) (derive-kv :slug (if title
                                    (sluj title)
                                    (sluj derived-title)))
      (not year) (derive-kv :year (if date
                                    (subs date 0 4)
                                    (shared/current-year)))
      (not location) (derive-kv :location "Copenhagen")
      (not language) (derive-kv :language "en")
      content (derive-kv :length (count content)))))

(defn md-info
  "Process a `markdown` file `path` into a Markdown info map."
  ([markdown path]
   (if-let [[frontmatter yaml] (re-find yaml-frontmatter markdown)]
     (let [markdown' (str/trim (subs markdown (count frontmatter)))]
       (expand-post
         (assoc (yaml->map yaml)
           :file path
           :ext "md"
           :hiccup (->hiccup (md/parse markdown'))
           :content markdown')))
     (expand-post
       {:file    path
        :ext     "md"
        :hiccup  (->hiccup (md/parse markdown))
        :content markdown})))
  ([path]
   (md-info (slurp path) path)))

(defn md-dossier
  "Hiccup-formatted Markdown posts located in `dir`."
  [dir]
  (map md-info (ext-filter "md" dir)))

(defn check!
  "Check the validity of the `posts` coll."
  [posts]
  (let [slugs (set (map :slug posts))]
    (assert (= (count posts) (count slugs)))
    posts))

(defn sort-posts
  "Sort `posts` by most recent."
  [posts]
  (reverse (sort-by :date posts)))

(defn entity-create
  "Ready a `post` + metadata for entity creation.

  With Datalevin the :file attribute is the :db.unique/identity key, so the post
  map is already transaction-ready and is returned unchanged. Kept as a named
  seam in case entity preparation is needed later."
  [entity]
  entity)

(comment
  (md-info "/Users/simongray/Code/simon.grays.blog/posts/spread.md")

  ;; Sort posts and confirm order by checking metadata
  (->> (md-dossier "test/resources/posts")
       (sort-posts)
       (map #(dissoc % :content :hiccup)))
  (map (comp keys entity-create) (md-dossier "test/resources/posts"))

  (derive-kv nil :sluj 123)
  (derive-kv {:derived #{:glen}} :sluj 123)

  ;; Ensure internal validity of post collection
  (check! (md-dossier "test/resources/posts"))              ; should be true
  (check! (->> (md-dossier "test/resources/posts")
               (map #(dissoc % :slug))))                    ; should be false
  #_.)