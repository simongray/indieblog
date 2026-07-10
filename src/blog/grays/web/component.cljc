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
           :type  "application/atom+xml"
           :title (str "Feed for " name)
           :href  (str url shared/feed-path)}]
   [:link {:rel "preconnect" :href "https://rsms.me/"}]
   [:link {:rel "stylesheet" :href "https://rsms.me/inter/inter.css"}]
   [:link {:rel "stylesheet" :href "/css/main.css?v=1"}]
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
    [tag attr (into [:a {:title "View this piece on its own page"
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

(defn not-found
  "The main content of the 404 page for a missing `year`/`slug` post."
  [year slug]
  [:main
   [:article
    [:h1 "Not found"]
    [:p "No such post: " [:strong year "/" slug]]
    [:p [:a.post-link {:href "/"} "↩ to main page"]]]])

(defn article
  [{:keys [date slug location] :as post} colour & {:keys [snippet?]}]
  (let [[headline content] (split-headline-content post)
        [year month day] (date-parts date)]
    [:article
     [:aside.metadata {:style {:background-color colour}}
      [:time {:datetime date}
       day " " month [:br]
       year]
      [:div.location location]]
     [:section.content
      (link-headline headline year slug)
      (if snippet?
        (into [:section.text.snippet] (snippet year slug content))
        (into [:section.text]
              (conj content
                    [:a.post-link {:href "/"} "↩ to main page"])))]]))

(defn articles
  "Snippet articles for `posts`, separated by horizontal rules."
  [posts]
  (interpose
    [:hr]
    (map #(article %1 %2 :snippet? true) posts (cycle palette))))

(defn header
  [{:keys [name tagline] :as conf}]
  [:header
   [:h1 [:a {:href  "/"
             :title "Go to the main page"}
         name]]
   (if (string? tagline)
     [:p tagline]
     tagline)])

(defn footer
  [{:keys [identity] :as conf}]
  (list
   [:hr]
   [:footer
    [:p "Subscribe to the " [:a {:href shared/feed-path} "RSS feed"] ", if you please."]
    (into [:address "You can also reach me here: "]
          (->> (sort-by (comp :label second) identity)
               (map (fn [[href {:keys [label]}]]
                      [:a {:href href} label]))
               (interpose ", ")))]))

(defn page
  "A full HTML page with the given `title` and `main` content."
  [title main conf & {:keys [reader?]}]
  (replicant/render
    [:html
     [:head
      (when title [:title title])                           ; dynamic
      (head conf)]                                          ; static
     [:body {:class (when reader? "reader")}
      (header conf)
      main
      (footer conf)]]))
