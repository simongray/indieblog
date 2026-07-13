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
           :href "/fonts/Literata.woff2"
           :as   "font"
           :type "font/woff2"
           :crossorigin "anonymous"}]
   [:link {:rel "stylesheet" :href "/css/main.css?v=16"}]
   (when identity
     (rel=me-links identity))
   (when bridgy-fed
     [:link {:rel   "me"
             :href  (str bridged-site url "/")
             :title "Bridgy Fed"}])))

(def palette
  "Accent colours cycled through when displaying articles."
  ["var(--flexoki-cyan-400)"
   "var(--flexoki-yellow-400)"
   "var(--flexoki-red-400)"
   "var(--flexoki-green-400)"
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
  "A single verified reply or plain mention as an h-cite list item. Reactions
  (like/repost/bookmark) are not shown this way; they go in the facepile."
  [{:mention/keys [source url kind author-name author-url published content]}]
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

(def ^:private reaction-kinds
  "The mention kinds shown as a compact facepile rather than as full comments: a
  like, repost or bookmark has no content of its own, only a face."
  #{:like :repost :bookmark})

(defn- face
  "A reaction as a compact h-cite: the author's cached avatar, or a monogram
  when there is none, linking to their profile. The name is kept for parsers
  and screen readers even though the pile shows only the face."
  [{:mention/keys [source url kind author-name author-url author-photo-cache]}]
  (let [href (or author-url url source)
        name (or author-name (shared/domain href))]
    [:li.face.h-cite
     [:a.p-author.h-card {:href href :title (str name ", " (kind->phrase kind))}
      (if author-photo-cache
        [:img.u-photo {:src author-photo-cache :alt ""}]
        [:span.monogram {:aria-hidden "true"} (str/upper-case (subs name 0 1))])
      [:span.p-name.visually-hidden name]]]))

(defn- native-comment
  "A single approved native comment as a list item; the mirror of `mention`,
  except that a native comment cites no external page: no h-cite, and its
  u-url is its own #comment-<id> anchor on this very page."
  [{:comment/keys [id author-name author-url published content]}]
  [:li.p-comment {:id (str "comment-" id)}
   [:a.p-author.h-card {:href author-url}
    (or author-name (shared/domain author-url))]
   " commented "
   [:a.u-url {:href (str "#comment-" id)}
    [:time.dt-published {:datetime published} published]]
   (when content
     [:blockquote.p-content content])])

(defn- response-date
  "When the mention or native comment `entity` was made: its publication date,
  or failing that when we received it."
  [entity]
  (or (:mention/published entity) (:comment/published entity)
      (:mention/received entity) (:comment/received entity)))

(defn- mention-form
  "A form for submitting a mention of the post at the absolute `target` URL by
  hand, for sites that do not send Webmentions themselves. It POSTs to our own
  /webmention endpoint, so a pasted URL is received, verified and displayed
  exactly like any other mention."
  [target]
  [:form.mention-form {:method "post" :action "/webmention"}
   [:label {:for "mention-source"}
    "Replied on your own site? Paste the URL of your reply:"]
   [:input#mention-source {:type        "url"
                           :name        "source"
                           :placeholder "https://example.com/my-reply"
                           :required    true}]
   [:input {:type "hidden" :name "target" :value target}]
   [:button {:type "submit"} "Send"]])

(defn- sign-in-form
  "A form for writing a comment directly on the post at the local `path`: the
  IndieWeb's Web sign-in, delegated to the :sign-in endpoint of conf (see the
  signin namespace). POSTing starts the flow; the comment form itself is
  served after the sign-in callback."
  [path]
  [:form.sign-in-form {:method "post" :action "/sign-in"}
   [:label {:for "sign-in-me"}
    "No reply to link? Sign in with your website and comment right here:"]
   [:input#sign-in-me {:type        "url"
                       :name        "me"
                       :placeholder "https://example.com"
                       :required    true}]
   [:input {:type "hidden" :name "path" :value path}]
   [:button {:type "submit"} "Sign in"]])

(defn responses
  "The responses to the post at the local `path`: likes, reposts and bookmarks
  gathered into a facepile; then replies, plain mentions and native `comments`
  interleaved by date; and finally the forms for responding right here (a
  Webmention by hand; Web sign-in when `conf` has :sign-in)."
  [{:keys [url sign-in] :as conf} path mentions comments]
  (let [{faces true talk false} (group-by (comp boolean reaction-kinds :mention/kind)
                                          mentions)
        talk (sort-by response-date (concat talk comments))]
    ;; The id is where the Webmention endpoint redirects a browser after a form
    ;; submission (see interceptors/webmention).
    [:section#comments.comments
     [:h2 "Responses"]
     (when (seq faces)
       (into [:ul.facepile] (map face) faces))
     (when (seq talk)
       (into [:ul]
             (map #(if (:comment/id %) (native-comment %) (mention %)))
             talk))
     (mention-form (str url path))
     (when sign-in
       (sign-in-form path))]))

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

(defn- author-card
  "The site `author` as a hidden p-author h-card linking to the canonical home:
  the implied, machine-readable byline shared by every h-entry and the h-feed.
  nil when there is no author."
  [author]
  (when author
    [:a.p-author.h-card {:href "/" :hidden true} author]))

(defn article
  [{:keys [date slug location reply-to syndication tags] :as post} colour
   {:keys [author bridgy-fed] :as conf}
   & {:keys [snippet? mentions comments reply-context]}]
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
      (author-card author)
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
      ;; The RSVP answer of a reply to an event; a <data> so the mf2 value
      ;; stays the bare yes/no/maybe/interested whatever the visible phrasing.
      (when-let [rsvp (and reply-to (:rsvp post))]
        [:p.rsvp-context "RSVP: " [:data.p-rsvp {:value rsvp} rsvp]])
      (for [[k [class label]] (dissoc response-verbs :reply-to)
            :let  [url (get post k)]
            :when url]
        [:p.response-context label " "
         [:a {:class class :href url} url]])
      (if snippet?
        ;; A brief snippet (e.g. a titleless note) is marked .short and skips
        ;; the multi-column layout (see the stylesheet), which only looks
        ;; right with enough text to fill the columns.
        (into [(if (< (count (shared/stringify content)) 500)
                 :section.text.snippet.short.p-summary
                 :section.text.snippet.p-summary)]
              (snippet year slug content))
        (list
          (into [:section.text.e-content] content)
          [:a.post-link {:href "/"} "↩ to main page"]
          (responses conf (post-href year slug) mentions comments)))]]))

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
  "A response `post` as a compact frontpage-strip card tinted `colour`: its
  date linking to the permalink, the verb and the target it responds to, and
  (for a reply with a body) a short excerpt. Deliberately not an h-entry: the
  canonical markup lives on the post's own page, so the strip must not double
  it."
  [{:keys [year slug date] :as post} colour]
  (let [[label target] (post-response post)
        excerpt        (when (:reply-to post)
                         (not-empty (shared/truncate 140 (post-description post))))]
    [:li.response {:style {:background-color colour}}
     [:a.date {:href (post-href year slug)} date]
     ;; The scheme is dropped from the visible label: the underline already
     ;; says it is a link, and the card has little room to waste.
     [:p.verb label " " [:a {:href target}
                         (str/replace target #"^https?://" "")]]
     (when excerpt
       [:p.excerpt excerpt])]))

(defn response-strip
  "The frontpage strip of the latest response `posts` (likes, reposts,
  bookmarks, replies), cycling the same palette as the article feed. Whether
  the strip renders at all is decided at the render site in page, not here."
  [posts]
  ;; TODO: an archive page for responses older than the few shown here, which are
  ;; otherwise reachable only by permalink once they drop off the strip.
  [:aside.responses {:aria-label "Latest responses"}
   (into [:ul] (map response-card posts (cycle palette)))])

(defn header
  [{:keys [name tagline] :as conf} & {:keys [frontpage?]}]
  [:header {:style {:background-color "var(--flexoki-magenta-400)"}}
   [(if frontpage? :h1.site-title :p.site-title)
    [:a {:href  "/"
         :title "Go to the main page"}
     name]]
   (if (string? tagline)
     [:p tagline]
     tagline)])

(defn- identity-link
  "The rel=me anchor for one `[href {:label ..}]` entry of the :identity conf;
  a mailto: href doubles as the h-card's u-email."
  [[href {:keys [label]}]]
  [(if (str/starts-with? href "mailto:") :a.u-email :a)
   {:href href :rel "me"} label])

(defn footer
  "The static <footer> content of every page; doubles as the representative
  h-card, so the author link must resolve to the site's canonical URL."
  [{:keys [identity author url photo] :as conf}]
  (list
   [:hr]
   [:footer {:style {:background-color "var(--flexoki-blue-400)"}}
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
               (map identity-link)
               (interpose ", ")))]))

(defn profile
  "The main content of the /about page: the site's full, visible h-card, i.e.
  the human-readable expansion of the terse one in the footer. The `page`
  headline stays a plain heading with no p-name: the h-card's p-name is the
  author, not the page title. The markdown body becomes the p-note (the mf2
  property for a bio)."
  [page {:keys [author url portrait locality country identity] :as conf}]
  (let [[headline content] (split-headline-content page)]
    [:article.h-card
     (when portrait
       [:img.portrait.u-photo {:src portrait :alt author}])
     headline
     [:p.whereabouts
      [:a.p-name.u-url.u-uid {:href (str url "/")} author]
      (when locality (list ", " [:span.p-locality locality]))
      (when country (list ", " [:span.p-country-name country]))]
     (into [:section.text.p-note] content)
     (into [:ul.elsewhere]
           (map (fn [entry] [:li (identity-link entry)]))
           (sort-by (comp :label second) identity))]))

(defn plain
  "The main content of a standalone page other than /about: its headline and
  body, with none of an article's h-entry markup; a page is neither a post nor
  a feed member."
  [page]
  (let [[headline content] (split-headline-content page)]
    (list headline
          (into [:section.text] content))))

(defn gone
  "The main content of the 410 page for a deleted post at `path`."
  [path]
  [:article
   [:h1 "Gone"]
   [:p "The post at " [:strong path] " has been deleted."]
   [:p [:a.post-link {:href "/"} "↩ to main page"]]])

(defn invalid-mention
  "The main content of the 400 page shown to a browser whose submitted
  Webmention `source` was rejected."
  [source]
  [:article
   [:h1 "Invalid Webmention"]
   [:p "The submitted URL" (when source (list " " [:strong source]))
    " was not accepted: it must be a public http(s) page, and the target an "
    "existing post on this site."]
   [:p [:a.post-link {:href "/"} "↩ to main page"]]])

(defn comment-form
  "The main content of the comment-writing page a visitor reaches by signing
  in as `me`: a form for commenting on the `post` at the local `path`,
  carrying the signed `token` that proves the sign-in (see the signin
  namespace)."
  [post path me token]
  [:article
   [:h1 "Write a comment"]
   [:p "Commenting on " [:a {:href path} (post-title post)]
    " as " [:strong (shared/domain me)] "."]
   [:form.comment-form {:method "post" :action "/comments"}
    [:label {:for "comment-content"} "Your comment, as plain text:"]
    [:textarea#comment-content {:name      "content"
                                :required  true
                                :rows      6
                                :maxlength shared/comment-max-length}]
    [:input {:type "hidden" :name "token" :value token}]
    [:button {:type "submit"} "Post comment"]]
   [:p "Comments can take a moment to appear after posting."]])

(defn sign-in-failed
  "The main content of the 400 page shown when a visitor's Web sign-in or
  comment could not be accepted."
  []
  [:article
   [:h1 "Sign-in failed"]
   [:p "Your sign-in could not be verified: it may have expired, or the "
    "authentication service rejected it. Head back to the post and try again."]
   [:p [:a.post-link {:href "/"} "↩ to main page"]]])

(defn- feed-meta
  "The h-feed's own `name`, `url`, and `author` as hidden mf2 properties, so the
  frontpage and tag feeds read as complete h-feed roots and not bare containers
  of h-entries. Hidden because the visible feed name is the site header, which
  sits outside <main>."
  [name url author]
  (list
    [:span.p-name {:hidden true} name]
    [:a.u-url {:href url :hidden true}]
    (author-card author)))

(defn page
  "A full HTML page with the given `title` and `main` content.

  The site title in the header is only an <h1> on the frontpage; other pages
  get their <h1> from the main content instead. Pages with a known canonical
  `path` also get a canonical link and Open Graph metadata."
  [title main conf & {:keys [reader? frontpage? h-feed? description path
                             responses]}]
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
        [:hr]
        ;; The frontpage and each tag page are h-feeds. Their p-name/u-url/
        ;; p-author are hidden mf2 (feed-meta): a parser needs them to read the
        ;; feed as one rooted whole, but the visible feed name is the site
        ;; header, which sits outside <main>.
        (let [feed? (or frontpage? h-feed?)]
          [:main#main {:tabindex "-1"
                       :class    (when feed? "h-feed")}
           (when feed?
             (feed-meta title (str (:url conf) path) (:author conf)))
           main])
        ;; The strip of the latest response posts, below the h-feed; kept
        ;; outside <main> so its cards are not read as feed h-entries.
        (when (seq responses)
          (response-strip responses))
        (footer conf)]])))
