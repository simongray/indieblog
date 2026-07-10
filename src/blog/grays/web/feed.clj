(ns blog.grays.web.feed
  "Functions for creating a blog feed."
  (:require [clj-rss.core :as rss]
            [replicant.string :as replicant]
            [tick.core :as t]
            [blog.grays.web.db :as db]
            [blog.grays.web.component :as c]
            [blog.grays.web.shared :as shared]))

;; TODO: assumes UTC, make t/zone configurable in conf
(defn date-str->instant
  [date-str]
  (t/instant (t/midnight (t/date date-str))))

(defn cdata
  [s]
  (str "<![CDATA[ " s " ]]>"))

(defn xml
  [{:keys [url name tagline language email author] :as conf} posts]
  (let [channel {:title       (shared/stringify name)
                 :link        url
                 :feed-url    (str url shared/feed-path)
                 :description (shared/stringify tagline)

                 ;; optional
                 :language    language}
        items   (map (fn [{:keys [date title year slug] :as post}]
                       (let [[_ nodes] (c/split-headline-content post)
                             article (into [:article] nodes)
                             content (cdata (replicant/render article))
                             link    (str url (c/post-href year slug))]
                         {:title            title
                          :link             link
                          :guid             link
                          :pubDate          (date-str->instant date)
                          :description      (->> (c/limit-nodes 400 nodes)
                                                 (shared/stringify))
                          "content:encoded" content

                          ;; optional
                          :author           (str email "(" author ")")}))
                     posts)]
    (apply rss/channel-xml channel items)))

(comment
  (map :title (db/get-posts (db/get-conn "test/resources/db/")))
  (xml blog.grays.web.service/conf (db/get-posts (db/get-conn "test/resources/db/")))
  #_.)
