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

(defn file-paths
  "All (canonical) file paths found recursively in `dir`."
  [dir]
  (->> (file-seq (io/file dir))
       (remove #(.isDirectory ^File %))
       (map #(.getCanonicalPath ^File %))))

(def yaml-frontmatter
  #"^---\n((?:\s|.)+?)---")

(defn yaml->map
  "Convert `yaml` kvs to a Clojure map."
  [yaml]
  (into {} (for [line (str/split yaml #"\n")]
             (let [[k v] (str/split (str/trim line) #":\s")]
               [(keyword k) v]))))

(defn assoc-derived
  "Assoc `v` for `k` in `post`, noting the derivation of `k` in :derived.

  Returns `post` unchanged when `v` is nil, since there is nothing to derive."
  [{:keys [derived] :as post} k v]
  (if (some? v)
    (assoc post
      k v
      :derived (conj (or derived #{}) k))
    post))

(defn hiccup-title
  "Derive a plain-text title from the first <h1> in `hiccup`, if any; its
  inline nodes are flattened to a single string, keeping :title a proper
  :db.type/string."
  [hiccup]
  (let [[tag :as h1] (first (elem/children hiccup))]
    (when (= :h1 tag)
      (shared/stringify h1))))

(defn file-slug
  "A URL slug from the basename of the file at `path`, sans directory and
  extension."
  [path]
  (some-> path (str/replace #"^.*/|\.[^.]*$" "") sluj not-empty))

(defn parse-tags
  "The set of tag slugs named by a comma-separated `tags` frontmatter string.

  Tags are authored already slug-shaped; slugifying is defensive, and is also
  what maps each to its /tags/<slug> URL. See the :tags schema in the db ns."
  [tags]
  (into #{} (comp (map str/trim)
                  (remove str/blank?)
                  (map sluj))
        (str/split tags #",")))

(defn expand-post
  "Derive additional metadata for a `post` entity."
  [{:keys [date title slug content hiccup file year location language tags] :as post}]
  (let [title' (or title (hiccup-title hiccup))]
    (cond-> post
      (not title) (assoc-derived :title title')
      ;; A note has no title to slugify, so it falls back to its filename, the
      ;; only other thing naming it.
      (not slug) (assoc-derived :slug (or (some-> title' sluj not-empty)
                                          (file-slug file)))
      (not year) (assoc-derived :year (if date
                                        (subs date 0 4)
                                        (shared/current-year)))
      (not location) (assoc-derived :location "Copenhagen")
      (not language) (assoc-derived :language "en")
      ;; Authored, not derived: the comma-separated string becomes a set of slugs.
      tags (assoc :tags (parse-tags tags))
      content (assoc-derived :length (count content)))))

(defn split-frontmatter
  "The [frontmatter body] of the `markdown` string: its parsed YAML frontmatter
  map ({} when there is none) and the trimmed body below it."
  [markdown]
  (let [[match yaml] (re-find yaml-frontmatter markdown)]
    [(if yaml (yaml->map yaml) {})
     (if match (str/trim (subs markdown (count match))) markdown)]))

(defn md->post
  "Process a `markdown` file `path` into a post entity map."
  ([markdown path]
   (let [[frontmatter body] (split-frontmatter markdown)]
     (expand-post
       (assoc frontmatter
         :file path
         :ext "md"
         :hiccup (->hiccup (md/parse body))
         :content body))))
  ([path]
   (md->post (slurp path) path)))

(defn read-posts
  "Post entity maps for the Markdown files located in `dir`."
  [dir]
  (->> (file-paths dir)
       (filter #(= "md" (file-ext %)))
       (map md->post)))

(defn check!
  "Check the validity of the `posts` coll, throwing if slugs are not unique."
  [posts]
  (let [dupes (->> (map :slug posts)
                   (frequencies)
                   (keep (fn [[slug n]] (when (> n 1) slug)))
                   (set))]
    (when (seq dupes)
      (throw (ex-info (str "Duplicate post slugs: " (str/join ", " dupes))
                      {:slugs dupes})))
    posts))

(defn sort-posts
  "Sort `posts` by most recent."
  [posts]
  (reverse (sort-by :date posts)))

(comment
  (md->post "/Users/simongray/Code/simon.grays.blog/posts/spread.md")

  ;; Sort posts and confirm order by checking metadata
  (->> (read-posts "test/resources/posts")
       (sort-posts)
       (map #(dissoc % :content :hiccup)))

  (assoc-derived nil :sluj 123)
  (assoc-derived {:derived #{:glen}} :sluj 123)

  ;; Ensure internal validity of post collection
  (check! (read-posts "test/resources/posts"))              ; should return posts
  (check! (->> (read-posts "test/resources/posts")
               (map #(dissoc % :slug))))                    ; should throw
  #_.)
