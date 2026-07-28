(ns blog.grays.web.shared
  "Various shared functions and data."
  (:require [clojure.string :as str])
  #?(:clj (:import [java.time LocalDateTime])))

(def feed-path
  "/feed")

(def tags-path
  "/tags")

(def pages
  "The standalone pages, in nav order. Each is served at /<slug> from a markdown
  file of the same name in the posts dir. The single list read by everyone:
  db/page-slugs derives from it, service generates a route per slug, the sitemap
  lists them under their own URLs, and the frontpage masthead links them."
  [{:slug "about" :label "about"}
   {:slug "now" :label "now"}])

(def nav-items
  "What the frontpage masthead links, in order: the standalone pages, then the
  tag index. The latter is generated rather than written, so it belongs here
  and not in `pages`, which is the list of markdown-backed pages."
  (conj (mapv (fn [{:keys [slug label]}]
                {:href (str "/" slug) :label label})
              pages)
        {:href tags-path :label "tags"}))

(def months
  {1  "January"
   2  "February"
   3  "March"
   4  "April"
   5  "May"
   6  "June"
   7  "July"
   8  "August"
   9  "September"
   10 "October"
   11 "November"
   12 "December"})

(defn stringify
  "Turn a `hiccup` tree into a single string.

  This is used to convert anything that can potentially be Hiccup into a string
  in cases where only strings are allowed, e.g. RSS title and description."
  [hiccup]
  (if (vector? hiccup)
    (-> (->> (tree-seq vector? seq hiccup)
             (filter string?)
             (str/join))
        (str/replace #"\s+" " ")
        (str/trim))
    hiccup))

(defn current-year
  "The current year as a string (matching the :year post attribute)."
  []
  #?(:clj  (str (.getYear ^LocalDateTime (LocalDateTime/now)))
     :cljs (str (.getFullYear (js/Date.)))))

(defn compact
  "The map `m` without its nil values."
  [m]
  (into {} (remove (comp nil? val)) m))

(defn domain
  "The domain of the absolute `url`, e.g. \"example.com\"."
  [url]
  (second (re-find #"//([^/]+)" (str url))))

(defn truncate
  "The string `s`, cut back to a word boundary within `n` characters and given
  an ellipsis when it was longer than that."
  [n s]
  (when s
    (if (<= (count s) n)
      s
      (-> (subs s 0 n)
          (str/replace #"\s+\S*$" "")
          (str "…")))))

(def comment-max-length
  "The cap on a native comment's length: room for a real reply, and a bound on
  what a stranger can make us store."
  2000)

(comment
  (stringify [:h1 {} "sdsd " [:a {:href "sdsd"} "jojn"]])
  (compact {:a 1 :b nil})
  (domain "https://example.com/some/page")
  #_.)
