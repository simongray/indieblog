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

(defn expand-article
  "Derive additional metadata for an `article` entity."
  [{:keys [date file title slug content hiccup year location language] :as article}]
  (let [derived-title (hiccup-title hiccup)]
    (cond-> article
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

(defn md-article
  "Process a `markdown` file `path` into an article entity."
  ([markdown path]
   (if-let [[frontmatter yaml] (re-find yaml-frontmatter markdown)]
     (let [content (subs markdown (count frontmatter))]
       (expand-article
         (assoc (yaml->map yaml)
           :file path
           :hiccup (->hiccup (md/parse content))
           :content content)))
     (expand-article
       {:file    path
        :hiccup  (->hiccup (md/parse markdown))
        :content markdown})))
  ([path]
   (md-article (slurp path) path)))

(defn md-articles
  "Hiccup-formatted Markdown articles located in `dir`."
  [dir]
  (map md-article (ext-filter "md" dir)))

(defn check!
  "Check the validity of the `articles` coll."
  [articles]
  (let [slugs (set (map :slug articles))]
    (assert (= (count articles) (count slugs)))
    articles))

(defn sort-articles
  "Sort `articles` by most recent."
  [articles]
  (reverse (sort-by :date articles)))

(defn entity-create
  "Ready an `article` + metadata for initial entity creation."
  [{:keys [file] :as entity}]
  (assoc entity
    :db/ident file))

(defn entity-update
  "Ready an `article` + metadata for updating an existing entity."
  [article]
  (update-keys (entity-create article)
               (fn [k]
                 (if (not= k :db/ident)
                   (keyword (str (name k) "'"))
                   k))))

(comment
  ;; Sort articles and confirm order by checking metadata
  (->> (md-articles "test/resources/articles")
       (sort-articles)
       (map #(dissoc % :content :hiccup)))
  (map (comp keys entity-create) (md-articles "test/resources/articles"))
  (map (comp keys entity-update) (md-articles "test/resources/articles"))

  (derive-kv nil :sluj 123)
  (derive-kv {:derived #{:glen}} :sluj 123)

  ;; Ensure internal validity of article collection
  (check! (md-articles "test/resources/articles"))          ; should be true
  (check! (->> (md-articles "test/resources/articles")
               (map #(dissoc % :slug))))                    ; should be false
  #_.)