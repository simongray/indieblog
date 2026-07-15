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

(def ^:private bridged-site
  "Bridgy Fed's proxy of a bridged home page, which is what the bridged account
  links to. A rel=me link back to it is what earns the profile a verified tick
  in Mastodon."
  "https://web.brid.gy/r/")

(defn head
  "The static <head> content of every page."
  [{:keys [identity name url webmention-endpoint indieauth micropub-endpoint
           bridgy-fed]
    :as   conf}]
  (list
   [:meta {:charset "UTF-8"}]
   [:meta {:name    "viewport"
           :content "width=device-width, initial-scale=1.0"}]
   [:link {:rel   "alternate"
           :type  "application/rss+xml"
           :title (str "Feed for " name)
           :href  (str url shared/feed-path)}]
   (when webmention-endpoint
     [:link {:rel "webmention" :href webmention-endpoint}])
   (when-let [{:keys [authorization-endpoint token-endpoint]} indieauth]
     (list
      [:link {:rel "authorization_endpoint" :href authorization-endpoint}]
      [:link {:rel "token_endpoint" :href token-endpoint}]))
   (when micropub-endpoint
     [:link {:rel "micropub" :href micropub-endpoint}])
   [:link {:rel  "preload"
           :href "/fonts/InterVariable.woff2"
           :as   "font"
           :type "font/woff2"
           :crossorigin "anonymous"}]
   [:link {:rel "stylesheet" :href "/css/main.css?v=10"}]
   (when identity
     (rel=me-links identity))
   (when bridgy-fed
     [:link {:rel   "me"
             :href  (str bridged-site url "/")
             :title "Bridgy Fed"}])))

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
  "The [headline content] of `post`: its <h1>, and the body below it."
  [{:keys [title hiccup derived] :as post}]
  (cond
    ;; A derived title *is* the first element of the body.
    (contains? derived :title)
    (let [[headline & content] (elem/children hiccup)]
      [headline (vec content)])

    ;; A frontmatter title is not in the body at all, so synthesise it.
    title
    [[:h1 title] (vec (elem/children hiccup))]

    ;; A note has no title, and therefore no headline; the caller must cope
    ;; with nil. That is the point: having a name is what an article has and a
    ;; note has not.
    :else
    [nil (vec (elem/children hiccup))]))

(defn post-description
  "A short plain-text summary of `post` for feeds and page metadata."
  [post]
  (let [[_ nodes] (split-headline-content post)]
    (shared/stringify (limit-nodes 400 nodes))))

(defn post-title
  "A title for `post` where one is required regardless, e.g. the <title> element
  or an RSS item; a note has none, so it falls back to an excerpt."
  [post]
  ;; Never a p-name: no parser sees this, only a browser tab.
  (or (:title post)
      (shared/truncate 60 (post-description post))))

(defn not-found
  "The main content of the 404 page for a missing `path`."
  [path]
  [:article
   [:h1 "Not found"]
   [:p "No such page: " [:strong path]]
   [:p [:a.post-link {:href "/"} "↩ to main page"]]])

(def ^:private kind->phrase
  "What each kind of webmention says its source did to the post."
  {:reply    "replied"
   :like     "liked this"
   :repost   "reposted this"
   :bookmark "bookmarked this"
   :mention  "mentioned this"})

(defn mention
  "A single verified webmention as an h-cite list item."
  [{:mention/keys [source url kind author-name author-url published content]}]
  ;; TODO: display :mention/author-photo. We store it but cannot show it: a
  ;; remote <img> would need an img-src CSP exception, and hotlinking hands
  ;; every reader's IP to whichever instance hosts the avatar. The fix is to
  ;; cache the photos under the indieweb-dir and serve them ourselves, keeping
  ;; default-src 'self' intact.
  ;;
  ;; We link to the url the source claims for itself, not the source URL that
  ;; was POSTed to us; the db schema says why those differ.
  (let [href (or url source)]
    [:li.p-comment.h-cite
     [:a.p-author.h-card {:href (or author-url href)}
      (or author-name (shared/domain href))]
     " " (kind->phrase kind) " "
     [:a.u-url {:href href}
      (if published
        [:time.dt-published {:datetime published}
         (first (str/split published #"T"))]
        "↗")]
     (when content
       [:blockquote.p-content content])]))

(defn comments
  "The webmentions of a post, listed as h-cite comments; nil when there are
  none."
  [mentions]
  (when (seq mentions)
    [:section.comments
     [:h2 "Mentions"]
     (into [:ul] (map mention) mentions)]))

(def ^:private response-verbs
  "Each response verb a post can carry in its frontmatter, keyed by its
  attribute and mapped to the mf2 class and visible label of the link it
  renders. The canonical list of the attributes themselves is
  db/response-verb-attrs; article renders the non-reply verbs inline, and
  responses renders all four in the frontpage strip."
  {:reply-to    ["u-in-reply-to" "Replied to"]
   :like-of     ["u-like-of" "Liked"]
   :repost-of   ["u-repost-of" "Reposted"]
   :bookmark-of ["u-bookmark-of" "Bookmarked"]})

(defn article
  [{:keys [date slug location reply-to syndication tags] :as post} colour
   {:keys [author bridgy-fed] :as conf}
   & {:keys [snippet? mentions reply-context]}]
  (let [[headline content] (split-headline-content post)
        ;; Snippets on the frontpage are demoted to <h2> so that each page
        ;; keeps a single <h1>; .headline preserves the styling either way.
        ;; A note has no headline at all; see split-headline-content.
        headline (some-> headline
                         (assoc 0 (if snippet? :h2.headline.p-name :h1.headline.p-name)))
        [year month day] (date-parts date)]
    [:article.h-entry
     [:aside.metadata {:style {:background-color colour}}
      [:time.dt-published {:datetime date}
       day " " month [:br]
       year]
      [:div.location.p-location location]
      ;; p-category per tag; the "#" is decorative (CSS), so the mf2 value stays
      ;; the bare slug. Each links to its /tags page.
      (when (seq tags)
        (into [:ul.tags {:aria-label "Tags"}]
              (map (fn [tag]
                     [:li [:a.p-category {:href (str "/tags/" tag)} tag]]))
              (sort tags)))
      ;; Machine-readable authorship; hidden since the byline is implied.
      (when author
        [:a.p-author.h-card {:href "/" :hidden true} author])
      ;; An article's u-url rides on its headline link; a note has no headline,
      ;; so its permalink is stated here instead. Every h-entry owes one.
      (when-not headline
        [:a.u-url {:href (post-href year slug) :hidden true}])
      ;; The link that asks Bridgy Fed to federate this post, and the reason a
      ;; Webmention to it means anything. Two subtleties, both from their docs:
      ;; it must stay *outside* the e-content below, or Mastodon renders a link
      ;; preview of fed.brid.gy inside the post; and the u-bridgy-fed class
      ;; stops mf2 parsers reading an empty <a> as an implied u-url.
      (when bridgy-fed
        [:a.u-bridgy-fed {:href bridgy-fed :hidden true}])
      ;; Machine-readable POSSE copies, e.g. for Bridgy backfeed discovery.
      (when syndication
        (for [url (str/split syndication #"\s+")]
          [:a.u-syndication {:href url :hidden true} (shared/domain url)]))]
     [:section.content
      (when headline
        (link-headline headline year slug))
      (when reply-to
        (let [{:context/keys [title author]} reply-context]
          [:p.reply-context "In reply to "
           [:a.u-in-reply-to {:href reply-to} (or title reply-to)]
           (when author (list " by " author))]))
      (for [[k [class label]] (dissoc response-verbs :reply-to)
            :let  [url (get post k)]
            :when url]
        [:p.response-context label " "
         [:a {:class class :href url} url]])
      (if snippet?
        (into [:section.text.snippet.p-summary] (snippet year slug content))
        (list
          (into [:section.text.e-content] content)
          [:a.post-link {:href "/"} "↩ to main page"]
          (comments mentions)))]]))

(defn articles
  "Snippet articles for `posts`, separated by horizontal rules."
  [posts conf]
  (interpose
    [:hr]
    (map #(article %1 %2 conf :snippet? true)
         posts
         (cycle palette))))

(defn tagged
  "The main content of a tag page: an <h1> naming the `tag`, then the h-feed of
  the `posts` that carry it. A tag page has no title of its own, so the <h1>
  here is where the page gets one."
  [tag posts conf]
  (cons [:h1 "Tagged #" tag]
        (articles posts conf)))

(defn- post-response
  "The [label target] a response `post` displays: its verb's label and the URL
  that verb points at. nil for an article."
  [post]
  (some (fn [[k [_ label]]]
          (when-let [target (get post k)]
            [label target]))
        response-verbs))

(defn response-card
  "A response `post` as a compact frontpage-strip card: its date linking to the
  permalink, the verb and the target it responds to, and (for a reply with a
  body) a short excerpt. Deliberately not an h-entry: the canonical markup lives
  on the post's own page, so the strip must not double it."
  [{:keys [year slug date] :as post}]
  (let [[label target] (post-response post)
        excerpt        (when (:reply-to post)
                         (not-empty (shared/truncate 140 (post-description post))))]
    [:li.response
     [:a.date {:href (post-href year slug)} date]
     [:p.verb label " " [:a {:href target} target]]
     (when excerpt
       [:p.excerpt excerpt])]))

(defn responses
  "The frontpage strip of the latest response `posts` (likes, reposts, bookmarks,
  replies); nil when there are none."
  [posts]
  ;; TODO: an archive page for responses older than the few shown here, which are
  ;; otherwise reachable only by permalink once they drop off the strip.
  (when (seq posts)
    (into [:ul.responses {:aria-label "Latest responses"}]
          (map response-card)
          posts)))

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
  [{:keys [identity author url photo] :as conf}]
  (list
   [:hr]
   [:footer
    [:p "Subscribe to the " [:a {:href shared/feed-path} "RSS feed"] ", if you please."]
    (into [:address.h-card
           ;; Hidden, since the page has no room for a portrait; but a bridged
           ;; profile with no photo is one Bridgy Fed refuses to bridge at all.
           (when photo
             [:img.u-photo {:src photo :alt "" :hidden true}])
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
  [title main conf & {:keys [reader? frontpage? h-feed? description path before-main]}]
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
                [:meta {:property "og:description" :content description}])
              ;; The bridged copy of this post, so that it turns up when someone
              ;; searches the fediverse for its URL, which is how people there
              ;; find a post to reply to. Posts only; the frontpage has no
              ;; bridged copy.
              (when-let [bridgy-fed (and (not frontpage?) (:bridgy-fed conf))]
                [:link {:rel  "alternate"
                        :type "application/activity+json"
                        :href (str bridgy-fed "r/" canonical)}]))))
        ;; static
        (head conf)]
       [:body {:class (when reader? "reader")}
        [:a.skip-link {:href "#main"} "Skip to main content"]
        (header conf :frontpage? frontpage?)
        ;; A frontpage-only strip that sits between the header and the h-feed;
        ;; kept out of <main> so its cards are not read as feed h-entries.
        before-main
        ;; The frontpage and each tag page are h-feeds. TODO (Item 11): give the
        ;; feed its own p-name/u-url/p-author so it is a complete h-feed root.
        [:main#main {:tabindex "-1"
                     :class    (when (or frontpage? h-feed?) "h-feed")} main]
        (footer conf)]])))
