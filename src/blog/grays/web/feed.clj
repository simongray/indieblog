(ns blog.grays.web.feed
  "Functions for creating a blog feed and sitemap."
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
  "The RSS feed XML for `posts`. `title`, `description` and `feed-url` default to
  the site-wide values in `conf`; a per-tag feed overrides them."
  [{:keys [url name tagline language email author] :as conf} posts
   & {:keys [title description feed-url]}]
  (let [channel {:title         (shared/stringify (or title name))
                 :link          url
                 :feed-url      (or feed-url (str url shared/feed-path))
                 :description   (shared/stringify (or description tagline))

                 ;; optional
                 :language      language
                 :lastBuildDate (some->> (map :date posts) sort last date-str->instant)
                 :ttl           1440}
        items   (map (fn [{:keys [date year slug] :as post}]
                       (let [[_ nodes] (c/split-headline-content post)
                             article (into [:article] nodes)
                             content (cdata (replicant/render article))
                             link    (str url (c/post-href year slug))]
                         {:title            (c/post-title post)
                          :link             link
                          :guid             link
                          :pubDate          (date-str->instant date)
                          :description      (c/post-description post)
                          "content:encoded" content

                          ;; optional
                          :author           (str email " (" author ")")}))
                     posts)]
    (apply rss/channel-xml channel items)))

(defn sitemap-xml
  "An XML sitemap covering the frontpage and the given `posts`."
  [{:keys [url] :as conf} posts]
  (str
    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
    (replicant/render
      (into [:urlset {:xmlns "http://www.sitemaps.org/schemas/sitemap/0.9"}
             [:url
              [:loc (str url "/")]
              (when-let [date (some->> (map :date posts) sort last)]
                [:lastmod date])]]
            (map (fn [{:keys [year slug date]}]
                   [:url
                    [:loc (str url (c/post-href year slug))]
                    [:lastmod date]]))
            posts))))

(comment
  (map :title (db/get-posts (db/get-conn "test/resources/db/")))
  (xml blog.grays.web.service/conf (db/get-posts (db/get-conn "test/resources/db/")))
  #_.)
