(ns blog.grays.web.content
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [nextjournal.markdown :as md]
            [nextjournal.markdown.transform :refer [->hiccup]]
            [sluj.core :refer [sluj]])
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
  #"^---\n((?:\s|.)+)---")

(defn yaml->map
  "Convert `yaml` kvs to a Clojure map."
  [yaml]
  (into {} (for [line (str/split yaml #"\n")]
             (let [[k v] (str/split (str/trim line) #":\s")]
               [(keyword k) v]))))

(defn expand-frontmatter
  "Derive additional metadata for a frontmatter `m` and (optionally) `content`."
  [{:keys [title slug] :as m} & [content]]
  (cond-> m
    (not slug) (assoc :slug (sluj title))
    content (assoc :length (count content))))

(defn parse-md
  "Parse `markdown` as Hiccup, attaching potential frontmatter as metadata."
  [markdown]
  (if-let [[frontmatter yaml] (re-find yaml-frontmatter markdown)]
    (let [content (subs markdown (count frontmatter))]
      (with-meta
        (->hiccup (md/parse content))
        (expand-frontmatter (yaml->map yaml) content)))
    (->hiccup (md/parse markdown))))

(defn md-article
  "Process a Markdown `file` by parsing into Hiccup and attaching metadata."
  [file]
  (-> (slurp file)
      (parse-md)
      (vary-meta assoc :file file)))

(defn md-articles
  "Hiccup-formatted Markdown articles located in `dir`."
  [dir]
  (map md-article (ext-filter "md" dir)))

(defn check!
  "Check the validity of the `articles` coll."
  [articles]
  (let [slugs (set (map (comp :slug meta) articles))]
    (assert (= (count articles) (count slugs)))
    articles))

(defn sort-articles
  "Sort `articles` by most recent."
  [articles]
  (reverse (sort-by (comp :date meta) articles)))

(defn entity-create
  "Ready an `article` + metadata for initial entity creation."
  [article]
  (let [{:keys [file] :as entity} (meta article)]
    (assoc entity
      :db/ident file
      :hiccup article)))

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
  (map meta (sort-articles (md-articles "test/resources/articles")))
  (map (comp keys entity-create) (md-articles "test/resources/articles"))
  (map (comp keys entity-update) (md-articles "test/resources/articles"))

  ;; Ensure internal validity of article collection
  (check! (md-articles "test/resources/articles")) ; should be true
  (check! (->> (md-articles "test/resources/articles")
               (map #(vary-meta % dissoc :slug)))) ; should be false
  #_.)