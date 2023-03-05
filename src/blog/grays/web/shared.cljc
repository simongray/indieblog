(ns blog.grays.web.shared
  #?(:clj (:import [java.time LocalDateTime])))

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

(defn current-year
  []
  #?(:clj  (.getYear ^LocalDateTime (LocalDateTime/now))
     :cljs (.getFullYear (js/Date.))))
