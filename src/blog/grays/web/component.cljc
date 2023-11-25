(ns blog.grays.web.component
  (:require [clojure.string :as str]
            [rum.core :as rum]
            [blog.grays.web.shared :as shared])
  (:import [java.time LocalDateTime]))

(defn rel=me
  "Get a single rel=me :link/:a based on a `tag` and an `identity-kv`."
  [tag [href {:keys [label]} :as identity-kv]]
  [tag {:rel "me" :href href :title label}])

(defn rel=me-links
  "Get rel=me links to be used in the HTML head based on href+title `identity`."
  [identity]
  (map (partial rel=me :link) identity))

(defn head-content
  [{:keys [identity name url] :as conf}]
  [:<>
   [:meta {:charset "UTF-8"}]
   [:meta {:name    "viewport"
           :content "width=device-width, initial-scale=1.0"}]
   [:link {:rel   "alternate"
           :type  "application/atom+xml"
           :title (str "Feed for " name)
           :href  (str url shared/feed-path)}]
   [:link {:rel "preconnect" :href "https://rsms.me/"}]
   [:link {:rel "stylesheet" :href "https://rsms.me/inter/inter.css"}]
   [:link {:rel "stylesheet" :href "/css/main.css?v=1"}]
   (when identity
     (rel=me-links identity))])

(def theme
  ["var(--flexoki-green-400)" "var(--flexoki-red-400)"
   "var(--flexoki-blue-400)" "var(--flexoki-yellow-400)"
   "var(--flexoki-magenta-400)" "var(--flexoki-cyan-400)"
   "var(--flexoki-purple-400)"])

(defn limit-nodes
  "Limit `nodes` coll to nodes with cumulative text content within `limit`."
  [limit nodes]
  (loop [limit limit
         [node & rest-content] nodes
         ret   []]
    (let [node-size (count (apply str (filter string? node)))]
      (if (and node (< node-size limit))
        (recur (- limit node-size) rest-content (conj ret node))
        (or (not-empty ret)
            [(first nodes)])))))

(defn post-href
  [year slug]
  (str "/" year "/" slug))

(rum/defc snippet
  [year slug hiccup]
  (let [hiccup-snippet (limit-nodes 800 hiccup)]
    (conj hiccup-snippet
          (if (= hiccup-snippet hiccup)
            [:a {:title "View this piece on its own page"
                 :href  (post-href year slug)}
             "Permalink"]
            [:a {:title "Continue reading this piece"
                 :href  (post-href year slug)}
             "Read more ↪"]))))

(defn add-post-href
  [headline year slug]
  (let [[h1 strs] (partition-by string? headline)]
    (conj (vec h1) (into [:a {:title "View this piece on its own page"
                              :href  (post-href year slug)}]
                         strs))))

(defn date-format
  [utc-date-str]
  (let [[year month day] (str/split utc-date-str #"-|/| ")]
    [year
     (when month
       (get shared/months (parse-long month)))
     (when day
       (parse-long day))]))

(defn split-headline-content
  [{:keys [title slug hiccup derived] :as post}]
  (if (derived :title)
    [(second hiccup) (subvec hiccup 2)]
    [[:h1 (or title slug)] (subvec hiccup 1)]))

(rum/defc article-elem
  [{:keys [date slug location] :as post} colour & [snippet?]]
  (let [[headline hiccup'] (split-headline-content post)
        [year month day] (date-format date)
        headline-anchor (add-post-href headline year slug)]
    [:article
     [:aside.metadata {:style {:background-color colour}}
      [:time {:datetime date}
       day " " month [:br]
       year]
      [:div.location location]]
     [:section.content
      headline-anchor
      (if snippet?
        (into [:section.text.snippet] (snippet year slug hiccup'))
        (into [:section.text]
              (conj hiccup'
                    [:a {:href "/"} "↩ to main page"])))]]))

(rum/defc article-elems
  [posts]
  (interpose
    [:hr]
    (for [[post colour] (map vector posts (cycle theme))]
      (article-elem post colour true))))

(rum/defc header
  [{:keys [name tagline] :as conf}]
  [:header
   [:h1 [:a {:href  "/"
             :title "Go to the main page"}
         name]]
   (if (string? tagline)
     [:p tagline]
     tagline)])

(rum/defc footer
  [{:keys [identity messages] :as conf}]
  [:<>
   [:hr]
   [:footer
    [:p (rand-nth (:finished messages))]
    [:p "Subscribe to the " [:a {:href shared/feed-path} "RSS feed"] " if you please."]
    (into [:address "You can also reach me here: "]
          (->> (sort-by (comp :label second) identity)
               (map (fn [[href {:keys [label]}]]
                      [:a {:href href} label]))
               (interpose ", ")))]])

(defn html-page
  "A full HTML page. Needs a `title` and `main` content."
  [main {:keys [title] :as conf} & [reader?]]
  (rum/render-static-markup
    [:html
     [:head
      (when title [:title title])                           ; dynamic
      (head-content conf)]                                  ; static
     [:body (when reader? {:class "reader"})
      (header conf)
      main
      (footer conf)]]))
