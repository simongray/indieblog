(ns blog.grays.web.shared
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
    (letfn [(clean-whitespace [s] (str/trim (str/replace s #"\n|\t" "")))
            (find-strs [x] (cond
                             (vector? x)
                             (map find-strs x)

                             (string? x)
                             x))]
      (->> (map find-strs hiccup)
           (flatten)
           (remove nil?)
           (str/join)
           (clean-whitespace)))
    hiccup))

(defn current-year
  []
  #?(:clj  (.getYear ^LocalDateTime (LocalDateTime/now))
     :cljs (.getFullYear (js/Date.))))


(comment
  (stringify [:h1 {} "sdsd " [:a {:href "sdsd"} "jojn"]])
  #_.)
