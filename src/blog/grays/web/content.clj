(ns blog.grays.web.content
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [nextjournal.markdown :as md]
            [nextjournal.markdown.transform :refer [->hiccup]]
            [sluj.core :refer [sluj]]
            [blog.grays.web.shared :as shared])
  (:import [java.io File]))

(defn by-extension
  "Load a `dir` as a map of file extensions to file paths."
  [^File dir & [path-fn]]
  (let [filenames (.list dir)
        filepaths (map (or path-fn (partial str (.getAbsolutePath dir) "/"))
                       filenames)
        extension (comp second #(str/split % #"\."))]
    (group-by extension filepaths)))

(defn ext-filter
  "Filter files in `dir` by `extension`."
  [extension dir]
  (get (by-extension (io/file dir)) extension))

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
  [hiccup]
  (let [[tag attr content] (second hiccup)]
    (when (= :h1 tag)
      content)))

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

(defn md-post
  "Process a `markdown` file `path` into a post entity."
  ([markdown path]
   (if-let [[frontmatter yaml] (re-find yaml-frontmatter markdown)]
     (let [content (subs markdown (count frontmatter))]
       (expand-post
         (assoc (yaml->map yaml)
           :file path
           :hiccup (->hiccup (md/parse content))
           :content content)))
     (expand-post
       {:file    path
        :hiccup  (->hiccup (md/parse markdown))
        :content markdown})))
  ([path]
   (md-post (slurp path) path)))

(defn md-posts
  "Hiccup-formatted Markdown posts located in `dir`."
  [dir]
  (map md-post (ext-filter "md" dir)))

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
  "Ready a `post` + metadata for initial entity creation."
  [{:keys [file] :as entity}]
  (assoc entity
    :db/ident file))

(defn entity-update
  "Ready a `post` + metadata for updating an existing entity."
  [post]
  (update-keys (entity-create post)
               (fn [k]
                 (if (not= k :db/ident)
                   (keyword (str (name k) "'"))
                   k))))

(comment
  ;; Sort posts and confirm order by checking metadata
  (->> (md-posts "test/resources/posts")
       (sort-posts)
       (map #(dissoc % :content :hiccup)))
  (map (comp keys entity-create) (md-posts "test/resources/posts"))
  (map (comp keys entity-update) (md-posts "test/resources/posts"))

  (derive-kv nil :sluj 123)
  (derive-kv {:derived #{:glen}} :sluj 123)

  ;; Ensure internal validity of post collection
  (check! (md-posts "test/resources/posts"))                ; should be true
  (check! (->> (md-posts "test/resources/posts")
               (map #(dissoc % :slug))))                    ; should be false
  #_.)