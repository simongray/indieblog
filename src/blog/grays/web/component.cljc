(ns blog.grays.web.component
  "Functions for generating HTML."
  (:require [clojure.string :as str]
            [replicant.string :as replicant]
            [dk.cst.hiccup-tools.elem :as elem]
            [blog.grays.web.shared :as shared]))

(defn rel=me-links
  "Get rel=me <link>s to be used in the HTML head based on href+title `identity`."
  [identity]
  (for [[href {:keys [label]}] identity]
    [:link {:rel "me" :href href :title label}]))

(defn head
  "The static <head> content of every page."
  [{:keys [identity name url] :as conf}]
  (list
   [:meta {:charset "UTF-8"}]
   [:meta {:name    "viewport"
           :content "width=device-width, initial-scale=1.0"}]
   [:link {:rel   "alternate"
           :type  "application/rss+xml"
           :title (str "Feed for " name)
           :href  (str url shared/feed-path)}]
   [:link {:rel  "preload"
           :href "/fonts/InterVariable.woff2"
           :as   "font"
           :type "font/woff2"
           :crossorigin "anonymous"}]
   [:link {:rel "stylesheet" :href "/css/main.css?v=4"}]
   (when identity
     (rel=me-links identity))))

(def palette
  "Accent colours cycled through when displaying articles."
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
  (str "/posts/" year "/" slug))

(defn snippet
  "A limited version of the post `hiccup` linking to the post page."
  [year slug hiccup]
  (let [hiccup-snippet (limit-nodes 800 hiccup)]
    (conj hiccup-snippet
          (if (= hiccup-snippet hiccup)
            [:a.post-link {:title "View this piece on its own page"
                           :href  (post-href year slug)}
             "Permalink"]
            (list
             [:p "…"]
             [:a.post-link {:title "Continue reading this piece"
                            :href  (post-href year slug)}
              "Keep reading ↪"])))))

(defn link-headline
  "Wrap the content of a `headline` in a link to the post page."
  [headline year slug]
  (let [[tag attr children] (elem/parts headline)]
    [tag attr (into [:a.u-url {:title "View this piece on its own page"
                               :href  (post-href year slug)}]
                    children)]))

(defn date-parts
  "Split a `date-str` (\"YYYY-MM-DD\", or partial) into a [year month day]
  triple with a named month. Returns [nil nil nil] when the date is missing."
  [date-str]
  (if (str/blank? date-str)
    [nil nil nil]
    (let [[year month day] (str/split date-str #"-")]
      [year
       (some-> month parse-long shared/months)
       (some-> day parse-long)])))

(defn split-headline-content
  [{:keys [title slug hiccup derived] :as post}]
  (if (contains? derived :title)
    (let [[headline & content] (elem/children hiccup)]
      [headline (vec content)])
    [[:h1 (or title slug)] (vec (elem/children hiccup))]))

(defn post-description
  "A short plain-text summary of `post` for feeds and page metadata."
  [post]
  (let [[_ nodes] (split-headline-content post)]
    (shared/stringify (limit-nodes 400 nodes))))

(defn not-found
  "The main content of the 404 page for a missing `path`."
  [path]
  [:article
   [:h1 "Not found"]
   [:p "No such page: " [:strong path]]
   [:p [:a.post-link {:href "/"} "↩ to main page"]]])

(defn article
  [{:keys [date slug location] :as post} colour & {:keys [snippet? author]}]
  (let [[headline content] (split-headline-content post)
        ;; Snippets on the frontpage are demoted to <h2> so that each page
        ;; keeps a single <h1>; .headline preserves the styling either way.
        headline (assoc headline 0 (if snippet? :h2.headline.p-name :h1.headline.p-name))
        [year month day] (date-parts date)]
    [:article.h-entry
     [:aside.metadata {:style {:background-color colour}}
      [:time.dt-published {:datetime date}
       day " " month [:br]
       year]
      [:div.location.p-location location]
      ;; Machine-readable authorship; hidden since the byline is implied.
      (when author
        [:a.p-author.h-card {:href "/" :hidden true} author])]
     [:section.content
      (link-headline headline year slug)
      (if snippet?
        (into [:section.text.snippet.p-summary] (snippet year slug content))
        (list
          (into [:section.text.e-content] content)
          [:a.post-link {:href "/"} "↩ to main page"]))]]))

(defn articles
  "Snippet articles for `posts` by `author`, separated by horizontal rules."
  [posts author]
  (interpose
    [:hr]
    (map #(article %1 %2 :snippet? true :author author) posts (cycle palette))))

(defn header
  [{:keys [name tagline] :as conf} & {:keys [frontpage?]}]
  [:header
   [(if frontpage? :h1.site-title :p.site-title)
    [:a {:href  "/"
         :title "Go to the main page"}
     name]]
   (if (string? tagline)
     [:p tagline]
     tagline)])

(defn footer
  "The static <footer> content of every page; doubles as the representative
  h-card, so the author link must resolve to the site's canonical URL."
  [{:keys [identity author url] :as conf}]
  (list
   [:hr]
   [:footer
    [:p "Subscribe to the " [:a {:href shared/feed-path} "RSS feed"] ", if you please."]
    (into [:address.h-card
           "You can also reach me ("
           [:a.p-name.u-url.u-uid {:href (str url "/")} author]
           ") here: "]
          (->> (sort-by (comp :label second) identity)
               (map (fn [[href {:keys [label]}]]
                      [(if (str/starts-with? href "mailto:") :a.u-email :a)
                       {:href href :rel "me"} label]))
               (interpose ", ")))]))

(defn page
  "A full HTML page with the given `title` and `main` content.

  The site title in the header is only an <h1> on the frontpage; other pages
  get their <h1> from the main content instead. Pages with a known canonical
  `path` also get a canonical link and Open Graph metadata."
  [title main conf & {:keys [reader? frontpage? description path]}]
  (str
    "<!doctype html>"
    (replicant/render
      [:html {:lang (:language conf)}
       [:head
        ;; dynamic
        (when title [:title title])
        (when description
          [:meta {:name "description" :content description}])
        (when path
          (let [canonical (str (:url conf) path)]
            (list
              [:link {:rel "canonical" :href canonical}]
              [:meta {:property "og:url" :content canonical}]
              [:meta {:property "og:type" :content (if frontpage? "website" "article")}]
              (when title
                [:meta {:property "og:title" :content title}])
              (when description
                [:meta {:property "og:description" :content description}]))))
        ;; static
        (head conf)]
       [:body {:class (when reader? "reader")}
        [:a.skip-link {:href "#main"} "Skip to main content"]
        (header conf :frontpage? frontpage?)
        [:main#main {:tabindex "-1"
                     :class    (when frontpage? "h-feed")} main]
        (footer conf)]])))
