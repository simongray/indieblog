(ns blog.grays.web.component
  "Functions for generating HTML."
  (:require [clojure.string :as str]
            [replicant.string :as replicant]
            [dk.cst.hiccup-tools.elem :as elem]
            [blog.grays.web.shared :as shared]))

(defn rel=me-links
  "Get rel=me link elements to be used in the HTML head based on `identity`."
  [identity]
  (for [[href {:keys [label]}] identity]
    [:link {:rel   "me"
            :href  href
            :title label}]))

(def bridged-site
  "Bridgy Fed's proxy of a bridged home page, which is what the bridged account
  links to. A rel=me link back to it is what earns the profile a verified tick
  in Mastodon."
  "https://web.brid.gy/r/")

(defn head
  "The static <head> content of every page."
  [{:keys [identity name url webmention-endpoint indieauth micropub-endpoint
           bridgy-fed css-version]
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
    [:link {:rel         "preload"
            :href        "/fonts/Literata.woff2"
            :as          "font"
            :type        "font/woff2"
            :crossorigin "anonymous"}]
    [:link {:rel "stylesheet" :href (str "/css/main.css?v=" css-version)}]
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
   "var(--flexoki-magenta-400)"
   "var(--flexoki-blue-400)"
   "var(--flexoki-red-400)"
   "var(--flexoki-green-400)"
   "var(--flexoki-purple-400)"])

(def ink-palette
  "Ink colours cycled through the masthead labels. The -600 row, which is the
  one meant for coloured text on light paper: no blue or purple (the link and
  visited colours) and no yellow (too faint at this size)."
  ["var(--flexoki-red-600)"
   "var(--flexoki-orange-600)"
   "var(--flexoki-green-600)"
   "var(--flexoki-cyan-600)"
   "var(--flexoki-magenta-600)"])

(defn limit-nodes
  "Limit `nodes` coll to nodes with cumulative text content within `limit`."
  [limit nodes]
  (loop [limit limit
         [node & rest-content] nodes
         ret   []]
    ;; TODO: only top-level strings count; nested markup (e.g. [:p [:em ..]])
    ;; contributes nothing to the budget.
    (let [node-size (count (apply str (filter string? node)))]
      (if (and node (< node-size limit))
        (recur (- limit node-size) rest-content (conj ret node))
        (or (not-empty ret)
            ;; A bodyless post must come back empty, not [nil].
            (vec (take 1 nodes)))))))

(def snippet-limit 800)

(defn mark-continued
  "The post `content` with an empty #continued anchor where the frontpage
  snippet was cut, so \"Keep reading\" lands on the first omitted node. A
  separate marker rather than an id on the node: headings already have ids."
  [content]
  (let [[shown omitted] (split-at (count (limit-nodes snippet-limit content))
                                  content)]
    (if (seq omitted)
      (vec (concat shown [[:span#continued]] omitted))
      content)))

(defn snippet
  "A limited version of the post `hiccup` linking to the post page."
  [year slug hiccup]
  (let [hiccup-snippet (limit-nodes snippet-limit hiccup)
        truncated?     (not= hiccup-snippet hiccup)
        [title label]  (if truncated?
                         ["Continue reading this piece" "Keep reading"]
                         ["View this piece on its own page" "Permalink"])]
    (conj hiccup-snippet
          [:a.post-link {:title title
                         :href  (str (shared/post-href year slug)
                                     (when truncated? "#continued"))}
           label])))

(defn link-headline
  "Wrap the content of a `headline` in a link to the post page."
  [headline year slug]
  (let [[tag attr children] (elem/parts headline)]
    [tag attr (into [:a.u-url {:title "View this piece on its own page"
                               :href  (shared/post-href year slug)}]
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

(defn human-date
  "A date string (\"YYYY-MM-DD\" or a full ISO timestamp) as \"2 November 2025\",
  matching the way an article's metadata aside writes its date. Returns the
  input unchanged when it does not parse, e.g. a year-only partial date."
  [s]
  (let [[year month day] (date-parts (first (str/split (str s) #"T")))]
    (if (and year month day)
      (str day " " month " " year)
      s)))

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

(def home-link
  [:a.post-link {:href "/"} "Back to main page"])

(defn not-found
  "The main content of the 404 page for a missing `path`."
  [path]
  [:article
   [:h1 "Not found"]
   [:p "No such page: " [:strong path]]
   [:p home-link]])

(def kind->phrase
  "What each kind of webmention says its source did to the post."
  {:reply    "replied"
   :like     "liked this"
   :repost   "reposted this"
   :bookmark "bookmarked this"
   :mention  "mentioned this"})

(def response-rel
  "Carried by every link in a response except our own anchors: these URLs are a
  stranger's and we do not vouch for them. Two tokens because ugc says what
  they are and nofollow is what older engines act on."
  "nofollow ugc")

(def url-re
  "A bare URL in the plain text of a response. The trailing class drops the
  punctuation that ends a sentence rather than the URL."
  #"https?://[^\s<>\"]*[^\s<>\".,;:!?)\]]")

(defn break-url
  "The `url` as text with <wbr> hints before each path or query separator. The
  scheme is kept whole, or the first hint would land after \"https:/\"."
  [url]
  (let [[_ scheme tail] (re-matches #"(https?://)(.*)" url)]
    (interpose [:wbr] (cons scheme (str/split tail #"(?=[/?&#])")))))

(defn linkify
  "The plain text `s` of a response as hiccup, every URL in it made a link.
  Split drops trailing empty strings, which is what lines the two seqs up when
  `s` ends in a URL."
  [s]
  (let [links (map #(into [:a {:href % :rel response-rel}] (break-url %))
                   (re-seq url-re s))]
    (interleave (str/split s url-re) (concat links (repeat nil)))))

(defn mention
  "A single verified reply or plain mention as an h-cite list item. Reactions
  (like/repost/bookmark) are not shown this way; they go in the facepile."
  [depth {:mention/keys [source url kind author-name author-url published content]}]
  ;; We link to the url the source claims for itself, not the source URL that
  ;; was POSTed to us; the db schema says why those differ.
  (let [href (or url source)]
    [:li.p-comment.h-cite {:class (when (pos? depth) "reply")}
     [:a.p-author.h-card {:href (or author-url href) :rel response-rel}
      (or author-name (shared/domain href))]
     " " (kind->phrase kind) " "
     [:a.u-url {:href href :rel response-rel}
      (if published
        [:time.dt-published {:datetime published} (human-date published)]
        "↗")]
     (when content
       (into [:blockquote.p-content] (linkify content)))]))

(def reaction-kinds
  "The mention kinds shown as a compact facepile rather than as full comments: a
  like, repost or bookmark has no content of its own, only a face."
  #{:like :repost :bookmark})

(defn face
  "A reaction as a compact h-cite: the author's cached avatar, or a monogram
  when there is none, linking to their profile. The name is kept for parsers
  and screen readers even though the pile shows only the face."
  [{:mention/keys [source url kind author-name author-url author-photo-cache]}]
  (let [href (or author-url url source)
        name (or author-name (shared/domain href))]
    [:li.face.h-cite
     [:a.p-author.h-card {:href  href
                          :title (str name ", " (kind->phrase kind))
                          :rel   response-rel}
      (if author-photo-cache
        [:img.u-photo {:src author-photo-cache :alt ""}]
        [:span.monogram {:aria-hidden "true"} (str/upper-case (subs name 0 1))])
      [:span.p-name.visually-hidden name]]]))

(defn native-comment
  "A single approved native comment as a list item; the mirror of `mention`,
  except that a native comment cites no external page: no h-cite, and its
  u-url is its own #comment-<id> anchor on this very page."
  [{:comment/keys [id author-name author-url published content]}]
  [:li.p-comment {:id (str "comment-" id)}
   [:a.p-author.h-card {:href author-url :rel response-rel}
    (or author-name (shared/domain author-url))]
   " commented "
   [:a.u-url {:href (str "#comment-" id)}
    [:time.dt-published {:datetime published} (human-date published)]]
   (when content
     (into [:blockquote.p-content] (linkify content)))])

(defn response-date
  "When the mention or native comment `entity` was made: its publication date,
  or failing that when we received it."
  [entity]
  (or (:mention/published entity) (:comment/published entity)
      (:mention/received entity) (:comment/received entity)))

(defn mention-form
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

(defn sign-in-form
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

(defn thread
  "`talk` as [depth entity] pairs, each reply following what it replies to.

  A mention names its parent either by that parent's own url (a reply written
  on someone's own site) or, coming from Bridgy Fed, by the id the bridged
  instance gave it, which is the last segment of its source URL. Only one level
  of indentation is shown, so a reply to a reply joins its top-level ancestor."
  [talk]
  (let [index  (into {} (for [e talk
                              id [(:mention/url e)
                                  (some-> (:mention/source e)
                                          (str/split #"/") peek)]
                              :when id]
                          [id e]))
        parent (fn [e] (some #(or (index %) (index (peek (str/split % #"/"))))
                             (:mention/in-reply-to e)))
        ;; The take guards against a cycle; real threads are a handful deep.
        root   (fn [e] (last (take-while some? (take 10 (iterate parent e)))))]
    (->> (group-by root talk)
         (sort-by (comp response-date key))
         (mapcat (fn [[r es]]
                   (cons [0 r] (for [e (sort-by response-date es)
                                     :when (not= e r)]
                                 [1 e])))))))

(defn responses
  "The responses to the post at the local `path`: likes, reposts and bookmarks
  gathered into a facepile; then replies, plain mentions and native `comments`
  interleaved by date and threaded; and finally the forms for responding right
  here (a Webmention by hand; Web sign-in when `conf` has :sign-in)."
  [{:keys [url sign-in] :as conf} path mentions comments]
  (let [{faces true talk false} (group-by (comp boolean reaction-kinds :mention/kind)
                                          mentions)
        talk (thread (concat talk comments))]
    ;; The id is where the Webmention endpoint redirects a browser after a form
    ;; submission (see interceptors/webmention).
    [:section#comments.comments
     [:h2 "Responses"]
     (when (seq faces)
       (into [:ul.facepile] (map face) faces))
     (when (seq talk)
       (into [:ul]
             (map (fn [[depth e]]
                    (if (:comment/id e) (native-comment e) (mention depth e))))
             talk))
     (mention-form (str url path))
     (when sign-in
       (sign-in-form path))]))

(def response-verbs
  "Each response verb a post can carry in its frontmatter, keyed by its
  attribute and mapped to the mf2 class and visible label of the link it
  renders. The canonical list of the attributes themselves is
  db/response-verb-attrs; article renders the non-reply verbs inline, and the
  frontpage strip shows each response post's verb via post-response."
  {:reply-to    ["u-in-reply-to" "Replied to"]
   :like-of     ["u-like-of" "Liked"]
   :repost-of   ["u-repost-of" "Reposted"]
   :bookmark-of ["u-bookmark-of" "Bookmarked"]})

(defn author-card
  "The site `author` as a hidden p-author h-card linking to the canonical home:
  the implied, machine-readable byline shared by every h-entry and the h-feed.
  nil when there is no author."
  [author]
  (when author
    [:a.p-author.h-card {:href "/" :hidden true} author]))

(defn article
  "The `post` as an h-entry article, its metadata aside tinted `colour`, with
  the site-wide `conf` supplying author and Bridgy Fed markup.

  With :snippet? true, the truncated frontpage form; otherwise the full post
  page content, followed by the responses section built from :mentions,
  :comments and :reply-context."
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
        [:a.u-url {:href (shared/post-href year slug) :hidden true}])
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
        (if snippet?
          (link-headline headline year slug)
          headline))
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
            :let [url (get post k)]
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
          (into [:section.text.e-content] (mark-continued content))
          home-link
          (responses conf (shared/post-href year slug) mentions comments)))]]))

(defn articles
  "Snippet articles for `posts`, separated by horizontal rules."
  [posts conf]
  (interpose
    [:hr]
    (map #(article %1 %2 conf :snippet? true)
         posts
         (cycle palette))))

(defn tagged
  "The main content of a tag page: a header naming the `tag` and saying how many
  `posts` carry it, then the h-feed of those posts. A tag page has no title of
  its own, so the <h1> here is where the page gets one."
  [tag posts conf]
  (let [n (count posts)]
    (list* [:header
            [:h1.tag-title.p-name tag]
            [:p.tag-meta n (if (= 1 n) " post" " posts") " · "
             [:a {:href shared/tags-path} "all tags"]]]
           [:hr]
           (articles posts conf))))

(defn tag-index
  "The main content of /tags: every tag, with the number of posts carrying it,
  set as a back-of-the-book index. Each entry links to that tag's own page."
  [tag-counts]
  (list [:h1.page-title "Tags"]
        (into [:ul.tag-index]
              (map (fn [[tag n]]
                     [:li
                      [:a {:href (str shared/tags-path "/" tag)} tag]
                      [:span.count n]]))
              tag-counts)))

(defn post-response
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
        excerpt (when (:reply-to post)
                  (not-empty (shared/truncate 140 (post-description post))))]
    [:li.response {:style {:background-color colour}}
     ;; A plain <time>, not a dt-published: the card is deliberately not an
     ;; h-entry, so it must not claim the post's properties.
     [:a.date {:href (shared/post-href year slug)}
      [:time {:datetime date} (human-date date)]]
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

(defn masthead
  "The site nav, shown only on the frontpage: a line of the site's own pages
  under the tagline, each label in its own ink."
  []
  [:nav.masthead {:aria-label "Site"}
   (into [:ul]
         (map (fn [{:keys [href label]} colour]
                [:li [:a {:href href :style {:color colour}} label]])
              shared/nav-items
              (cycle ink-palette)))])

(defn curl
  "The turned corner shown on every page but the frontpage: the page below
  shows through the gap the fold left, with the way home written on it, and
  the folded part lies on the page beside it. The gap and all colours belong
  to the stylesheet; this SVG is the flap, layered by hand: shadow, paper,
  grain, the shading shared with the gap, and slivers of cut edge tapering
  along the two free edges. Every edge is a quadratic bowed just off
  straight, the crease toward the gap so the flap laps it."
  []
  [:a.curl {:href "/"}
   [:svg {:viewBox     "0 0 120 120"
          :width       120
          :height      120
          :aria-hidden "true"}
    [:defs
     [:path#curl-shape
      {:d "M 10 0 Q 25.6 51.6 43.2 102.6 Q 44 105 46.4 104.2 Q 82.6 90.2 120 80 Q 65.9 38.8 10 0 Z"}]
     [:pattern#curl-paper {:patternUnits "userSpaceOnUse"
                           :width        400
                           :height       400}
      [:image {:href "/images/paper.png" :width 400 :height 400}]]
     [:linearGradient#curl-shade {:gradientUnits "userSpaceOnUse"
                                  :x1            10.7
                                  :y1            127.8
                                  :x2            109.3
                                  :y2            -7.8}
      [:stop {:offset 0.23 :stop-color "#554422" :stop-opacity 0.2}]
      [:stop {:offset 0.46 :stop-color "#554422" :stop-opacity 0.05}]
      [:stop {:offset 0.54 :stop-color "#FFFCF0" :stop-opacity 0.12}]
      [:stop {:offset 0.61 :stop-color "#FFFCF0" :stop-opacity 0.3}]]
     [:filter#curl-lift {:x "-30%" :y "-30%" :width "160%" :height "160%"}
      [:feDropShadow {:dx -1 :dy 1 :stdDeviation 1
                      :flood-color "#000" :flood-opacity 0.3}]
      [:feDropShadow {:dx -4 :dy 5 :stdDeviation 3.5
                      :flood-color "#000" :flood-opacity 0.28}]]]
    [:g {:filter "url(#curl-lift)"}
     [:use.flap {:href "#curl-shape"}]
     [:use {:href "#curl-shape" :fill "url(#curl-paper)"}]
     [:use {:href "#curl-shape" :fill "url(#curl-shade)"}]
     [:path.edge {:d "M 10 0 Q 25.6 51.6 43.2 102.6 Q 23.9 52.2 10 0 Z"}]
     [:path.edge {:d "M 46.4 104.2 Q 82.6 90.2 120 80 Q 83.2 91.9 46.4 104.2 Z"}]]]
   [:span "Back"]])

(defn header
  "The full site header; only shown on the frontpage, where it carries the site
  nav. The title is plain text: the frontpage linking to itself would help no
  one."
  [{:keys [name tagline] :as conf}]
  [:header
   [:h1.site-title name]
   (if (string? tagline)
     [:p tagline]
     tagline)
   (masthead)])

(defn identity-link
  "The rel=me anchor for one `[href {:label ..}]` entry of the :identity conf;
  a mailto: href doubles as the h-card's u-email."
  [[href {:keys [label]}]]
  [(if (str/starts-with? href "mailto:") :a.u-email :a)
   {:href href :rel "me"} label])

(defn footer
  "The static <footer> content of every page; doubles as the representative
  h-card, so the author link must resolve to the site's canonical URL. The
  h-card sits on the wrapper rather than the <address>: the photo is one of its
  properties, and <address> is for the contact links alone."
  [{:keys [identity author url photo locality country] :as conf}]
  (list
    [:hr]
    [:footer
     [:div.h-card.colophon
      [:div.colophon-text
       [:p "By "
        [:a.p-name.u-url.u-uid {:href (str url "/")} author]
        (when (or locality country)
          (list " ("
                (interpose ", " (cond-> []
                                  locality (conj [:span.p-locality locality])
                                  country  (conj [:span.p-country-name country])))
                ")"))]
       (conj (into [:address "You can reach me here too: "]
                   (->> (sort-by (comp :label second) identity)
                        (map identity-link)
                        (interpose ", ")))
             ".")
       [:p "This blog also has an "
        [:a {:href shared/feed-path} "RSS feed"] "."]]
      (when photo
        (let [label (str "About " author)]
          [:a {:href "/about" :title label}
           [:img.u-photo {:src photo :alt label}]]))]]))

(defn plain
  "The main content of a standalone page: its headline and body, with none of an
  article's h-entry markup; a page is neither a post nor a feed member."
  [page]
  (let [[headline content] (split-headline-content page)]
    (list (some-> headline (assoc 0 :h1.page-title))
          (into [:section.text] content))))

(defn gone
  "The main content of the 410 page for a deleted post at `path`."
  [path]
  [:article
   [:h1 "Gone"]
   [:p "The post at " [:strong path] " has been deleted."]
   [:p home-link]])

(defn invalid-mention
  "The main content of the 400 page shown to a browser whose submitted
  Webmention `source` was rejected."
  [source]
  [:article
   [:h1 "Invalid Webmention"]
   [:p "The submitted URL" (when source (list " " [:strong source]))
    " was not accepted: it must be a public http(s) page, and the target an "
    "existing post on this site."]
   [:p home-link]])

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
   [:p home-link]])

(defn feed-meta
  "The h-feed's `url` and `author` as hidden mf2 properties, plus its `name` when
  given, so the feed reads as a complete h-feed root and not a bare container of
  h-entries."
  [name url author]
  (list
    (when name
      [:span.p-name {:hidden true} name])
    [:a.u-url {:href url :hidden true}]
    (author-card author)))

(defn page
  "A full HTML page with the given `title` and `main` content. The site header
  and nav appear only on the frontpage; other pages take their <h1> from the
  content. A known canonical `path` adds a canonical link and Open Graph
  metadata."
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
       [:body {:class (not-empty (str/join " " (cond-> []
                                                 reader? (conj "reader")
                                                 frontpage? (conj "frontpage"))))}
        [:a.skip-link {:href "#main"} "Skip to main content"]
        ;; The sheet of paper holds everything else. The frontpage class is
        ;; what tells the two coffee stain variants apart.
        [:div.sheet
         (if frontpage?
           (list (header conf) [:hr])
           (curl))
         ;; The frontpage and each tag page are h-feeds. Their u-url/p-author
         ;; are hidden mf2 (feed-meta); so is the frontpage's p-name, whose
         ;; visible counterpart is the site header outside <main>. A tag page
         ;; names itself in its own <h1>, so it supplies no hidden name.
         (let [feed? (or frontpage? h-feed?)]
           [:main#main {:tabindex "-1"
                        :class    (when feed? "h-feed")}
            (when feed?
              (feed-meta (when frontpage? title)
                         (str (:url conf) path)
                         (:author conf)))
            main])
         ;; The strip of the latest response posts, below the h-feed; kept
         ;; outside <main> so its cards are not read as feed h-entries.
         (when (seq responses)
           (list [:hr] (response-strip responses)))
         (footer conf)]]])))
