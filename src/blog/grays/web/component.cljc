(ns blog.grays.web.component
  (:require [rum.core :as rum]
            [blog.grays.web.hiccup :as h]))

(defn html-body
  [content]
  [:body
   [:div#app {:dangerouslySetInnerHTML {:__html (rum/render-html content)}}]
   [:script {:src (str "/js/compiled/main.js")}]])

(defn html-page
  "A full HTML page ready to be hydrated. Needs a `title` and `content`."
  [title content conf]
  (rum/render-static-markup
    [:html
     (h/html-head title conf)
     (html-body content)]))
