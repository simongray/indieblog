(ns blog.grays.web.feed
  (:require [clj-rss.core :as rss]
            [rum.core :as rum]
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
                             content (cdata (rum/render-static-markup article))
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
  (map :title (db/latest-posts (db/pconn "test/resources/db/")))
  (xml blog.grays.web.service/conf (db/latest-posts (db/pconn "test/resources/db/")))
  #_.)
