(ns blog.grays.web.shared
  "Various shared functions and data."
  (:require [clojure.string :as str])
  #?(:clj (:import [java.time LocalDateTime])))

(def feed-path
  "/feed")

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

(comment
  (stringify [:h1 {} "sdsd " [:a {:href "sdsd"} "jojn"]])
  (compact {:a 1 :b nil})
  (domain "https://example.com/some/page")
  #_.)
