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
    [:link {:rel   "search"
            :type  "application/opensearchdescription+xml"
            :title (shared/domain url)
            :href  (str url shared/opensearch-path)}]
    (when webmention-endpoint
      [:link {:rel "webmention" :href webmention-endpoint}])
    (when-let [{:keys [metadata authorization-endpoint token-endpoint]} indieauth]
      (list
        ;; The current IndieAuth discovery mechanism; the two legacy rels
        ;; below serve older clients.
        (when metadata
          [:link {:rel "indieauth-metadata" :href metadata}])
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
  "Ink colours cycled through the masthead labels: the -600 row minus blue
  and purple (the link colours) and yellow (too faint at this size)."
  ["var(--flexoki-red-600)"
   "var(--flexoki-orange-600)"
   "var(--flexoki-green-600)"
   "var(--flexoki-cyan-600)"
   "var(--flexoki-magenta-600)"])

(defn- top-level-text
  "The concatenated top-level strings of hiccup `node`; nested markup
  (e.g. [:p [:em ..]]) contributes nothing."
  [node]
  (apply str (filter string? node)))

(defn limit-nodes
  "Limit `nodes` coll to nodes with cumulative text content within `limit`."
  [limit nodes]
  (loop [limit limit
         [node & more] nodes
         ret   []]
    (let [node-size (count (top-level-text node))]
      (if (and node (< node-size limit))
        (recur (- limit node-size) more (conj ret node))
        (or (not-empty ret)
            ;; Show an over-limit first node rather than nothing; an empty
            ;; `nodes` still comes back empty, not [nil].
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
  matching the way an article's metadata aside writes its date. The time may
  also follow after a space, as microformats2 writes it. Returns the input
  unchanged when it does not parse, e.g. a year-only partial date."
  [s]
  (let [[year month day] (date-parts (first (str/split (str s) #"[T ]")))]
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

    ;; A note has no title, so no headline; the caller must cope with nil.
    :else
    [nil (vec (elem/children hiccup))]))

(defn post-description
  "A short plain-text summary of `post` for feeds and page metadata."
  [post]
  (let [[_ nodes] (split-headline-content post)]
    (shared/stringify (limit-nodes 400 nodes))))

(defn post-text
  "The visible text of `post` below its headline, as a reader sees it; no
  Markdown, no URLs."
  [post]
  (shared/stringify (second (split-headline-content post))))

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
  (list
    [:h1.page-title "Not found"]
    [:section.text
     [:p "No such page: " [:strong path]]
     [:p home-link]]))

(def kind->phrase
  "What each kind of webmention says its source did to the post."
  {:reply    "replied"
   :like     "liked this"
   :repost   "reposted this"
   :bookmark "bookmarked this"
   :mention  "mentioned this"})

(def response-rel
  "Carried by every link in a response except our own anchors: these URLs
  are a stranger's and we do not vouch for them."
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
  ;; was POSTed to us: a bridge (e.g. Bridgy Fed) POSTs a proxy page on its
  ;; own domain.
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
  except that a native comment cites no external page: it is an h-entry
  rather than an h-cite, and its u-url is its own #comment-<id> anchor on
  this very page.

  It needs a microformat root either way, or a parser reads its author, date
  and text as properties of the post's own h-entry."
  [{:comment/keys [id author-name author-url published content]}]
  [:li.p-comment.h-entry {:id (str "comment-" id)}
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
   ;; type text, not url: a bare domain ("example.com") must be accepted
   ;; (the endpoint assumes https for it, as the IndieAuth spec tells
   ;; clients to); inputmode/autocomplete shape the keyboard instead.
   [:input#sign-in-me {:type           "text"
                       :name           "me"
                       :placeholder    "example.com"
                       :required       true
                       :inputmode      "url"
                       :autocomplete   "url"
                       :autocapitalize "none"
                       :spellcheck     "false"}]
   [:input {:type "hidden" :name "path" :value path}]
   [:button {:type "submit"} "Sign in"]])

(defn comment-form
  "The form for writing a comment right here; `signed-in` carries the
  visitor's authenticated :me and the signed :token proving it.

  Shown in place of the response forms once a Web sign-in completes (see the
  signin namespace and interceptors/single-post)."
  [{:keys [me token]}]
  [:form.comment-form {:method "post" :action "/comments"}
   [:label {:for "comment-content"}
    "Signed in as " [:strong (shared/domain me)] ". Your comment, as plain text:"]
   [:textarea#comment-content {:name      "content"
                               :required  true
                               :rows      6
                               :maxlength shared/comment-max-length}]
   [:input {:type "hidden" :name "token" :value token}]
   [:button {:type "submit"} "Post comment"]
   [:p.note "Comments can take a moment to appear after posting."]])

(defn- bridge-id
  "The last path segment of `url`: the id a bridged instance (Bridgy Fed)
  knows a mention by."
  [url]
  (some-> url (str/split #"/") peek))

(defn thread
  "`talk` as [depth entity] pairs, each reply following what it replies to.

  A mention names its parent either by that parent's own url (a reply written
  on someone's own site) or, coming from Bridgy Fed, by the id the bridged
  instance gave it, which is the last segment of its source URL. Only one level
  of indentation is shown, so a reply to a reply joins its top-level ancestor."
  [talk]
  (let [index  (into {} (for [e talk
                              id [(:mention/url e)
                                  (bridge-id (:mention/source e))]
                              :when id]
                          [id e]))
        parent (fn [e] (some #(or (index %) (index (bridge-id %)))
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
  "The responses to the post at the local `path`: the `mentions` and native
  `comments` under `conf`, then the forms for responding right here.

  Likes, reposts and bookmarks gather into a facepile; replies, plain
  mentions and comments interleave by date and thread. `signed-in` swaps the
  response forms for the comment form."
  [{:keys [url sign-in] :as conf} path mentions comments & {:keys [signed-in]}]
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
     (if signed-in
       (comment-form signed-in)
       (list
         (mention-form (str url path))
         (when sign-in
           (sign-in-form path))))]))

(def response-verbs
  "The mf2 class and visible label of the link each response verb renders,
  keyed by its attribute; shared/response-verb-attrs lists the verbs, in the
  order they are rendered."
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
  :comments, :reply-context and :signed-in."
  [{:keys [date slug location reply-to syndication tags] :as post} colour
   {:keys [author bridgy-fed] :as conf}
   & {:keys [snippet? mentions comments reply-context signed-in]}]
  (let [[headline content] (split-headline-content post)
        ;; Snippets on the frontpage are demoted to <h2> so that each page
        ;; keeps a single <h1>; .headline preserves the styling either way.
        headline (some-> headline
                         (assoc 0 (if snippet? :h2.headline.p-name :h1.headline.p-name)))
        [year month day] (date-parts date)]
    [:article.h-entry
     [:aside.metadata {:style {:background-color colour}}
      [:time.dt-published {:datetime date}
       day " " month [:br]
       year]
      [:div.location.p-location location]
      ;; p-category per tag; its text, and so the mf2 value, is the bare slug.
      (when (seq tags)
        (into [:ul.tags {:aria-label "Tags"}]
              (map (fn [tag]
                     [:li [:a.p-category {:href (str "/tags/" tag)} tag]]))
              (sort tags)))
      (author-card author)
      ;; In a snippet, an article's u-url rides on its headline link; anywhere
      ;; else (a note, or an article on its own page) the permalink is stated
      ;; here instead.
      (when-not (and headline snippet?)
        [:a.u-url {:href (shared/post-href year slug) :hidden true}])
      ;; The link that asks Bridgy Fed to federate this post. Per their docs
      ;; it must stay *outside* the e-content (or Mastodon renders a link
      ;; preview), and u-bridgy-fed stops mf2 parsers reading an empty <a>
      ;; as an implied u-url.
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
      (for [k (remove #{:reply-to} shared/response-verb-attrs)
            :let [[class label] (response-verbs k)
                  url           (get post k)]
            :when url]
        [:p.response-context label " "
         [:a {:class class :href url} url]])
      (if snippet?
        ;; A brief snippet (e.g. a titleless note) is marked .short and
        ;; skips the multi-column layout (see the stylesheet).
        (into [(if (< (count (shared/stringify content)) 500)
                 :section.text.snippet.short.p-summary
                 :section.text.snippet.p-summary)]
              (snippet year slug content))
        (list
          (into [:section.text.e-content] (mark-continued content))
          home-link
          (responses conf (shared/post-href year slug) mentions comments
                     :signed-in signed-in)))]]))

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

(defn search-form
  "A form searching the posts, showing the query `q` when given."
  [q]
  [:form.search-form {:method "get" :action shared/search-path :role "search"}
   [:input {:type        "search"
            :name        "q"
            :value       q
            :placeholder "Search the blog"
            :aria-label  "Search the blog"}]
   [:button {:type "submit"} "Search"]])

(defn search-result
  "A `post` found by a search: its title linking to it, its date, and the
  `excerpt` parts that show where it matched."
  [{:keys [year slug date] :as post} excerpt]
  [:li
   [:a {:href (if (shared/page-slugs slug)
                (str "/" slug)
                (shared/post-href year slug))}
    (post-title post)]
   (when date
     [:time {:datetime date} (human-date date)])
   (into [:p.excerpt]
         (map (fn [{:keys [text match?]}]
                (if match? [:mark text] text)))
         excerpt)])

(defn search-results
  "The main content of the search page: the form holding the query `q`, the
  words it matched with `typos`, and the `results` as pairs of a post and its
  excerpt, best first."
  [q results typos]
  (list
    [:h1.page-title "Search"]
    (search-form q)
    (when q
      (let [n (count results)]
        (list
          [:p.search-meta n (if (= 1 n) " post" " posts") " for " [:strong q]]
          (for [[word matches] typos]
            [:p.search-meta "No post has the word " [:em word]
             "; close matches: " (str/join ", " (take 3 matches))])
          (when (seq results)
            (into [:ol.search-results]
                  (map #(apply search-result %))
                  results)))))))

(defn post-response
  "The [label target] a response `post` displays: its verb's label and the URL
  that verb points at. nil for an article."
  [post]
  (some (fn [k]
          (when-let [target (get post k)]
            [(second (response-verbs k)) target]))
        shared/response-verb-attrs))

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
     [:a.date {:href (shared/post-href year slug)}
      [:time {:datetime date} (human-date date)]]
     ;; Scheme dropped from the label: the underline already says link.
     [:p.verb label " " [:a {:href target}
                         (str/replace target #"^https?://" "")]]
     (when excerpt
       [:p.excerpt excerpt])]))

(defn response-strip
  "The frontpage strip of the latest response `posts` (likes, reposts,
  bookmarks, replies), cycling the same palette as the article feed."
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
  "The turned corner shown on every page but the frontpage, with the way
  home written on it. The gap and all colours belong to the stylesheet; this
  SVG is the flap, layered by hand: shadow, paper, grain, shading, and
  slivers of cut edge. Every edge is a quadratic bowed just off straight."
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
  h-card sits on the wrapper, not the <address>: the photo is one of its
  properties."
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
        [:a {:href shared/feed-path} "RSS feed"] "."]
       (search-form nil)]
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
  (list
    [:h1.page-title "Gone"]
    [:section.text
     [:p "The post at " [:strong path] " has been deleted."]
     [:p home-link]]))

(defn invalid-mention
  "The main content of the 400 page shown to a browser whose submitted
  Webmention `source` was rejected."
  [source]
  (list
    [:h1.page-title "Invalid Webmention"]
    [:section.text
     [:p "The submitted URL" (when source (list " " [:strong source]))
      " was not accepted: it must be a public http(s) page, and the target an "
      "existing post on this site."]
     [:p home-link]]))

(defn sign-in-failed
  "The main content of the 400 page shown when a visitor's Web sign-in or
  comment could not be accepted."
  []
  (list
    [:h1.page-title "Sign-in failed"]
    [:section.text
     [:p "Your sign-in could not be verified: it may have expired, or the "
      "authentication service rejected it. Head back to the post and try again."]
     [:p home-link]]))

(defn sign-in-unsupported
  "The main content of the 400 page shown when a sign-in claim could not even
  begin: the claimed homepage must advertise a way to authenticate its owner
  (see the auth namespace)."
  []
  (list
    [:h1.page-title "Sign-in failed"]
    [:section.text
     [:p "To sign in, the page at your URL must either advertise your own "
      [:a {:href "https://indieauth.net/"} "IndieAuth"] " server, or link "
      "your GitHub or Mastodon profile with " [:code "rel=me"] ", for example:"]
     [:pre [:code "<a href=\"https://github.com/you\" rel=\"me\">GitHub</a>"]]
     [:p "…with that profile linking back to your URL: GitHub's website "
      "field, or a link in your Mastodon bio or profile fields. Then head "
      "back to the post and try again."]
     [:p home-link]]))

(defn sign-in-chooser
  "The main content of the provider-choice page: one link per option in
  `choices`, the several ways the page at `me` offers to authenticate.

  Each option (see auth/discovered-options) carries a :choose-href back to
  /auth with that choice pinned."
  [me choices]
  (list
    [:h1.page-title "Sign in"]
    [:section.text
     [:p "The page at " [:strong (or (shared/domain me) me)]
      " offers more than one way to sign in:"]
     (into [:ul.sign-in-choices]
           (map (fn [{:keys [kind user instance endpoint choose-href]}]
                  [:li
                   [:a {:href choose-href}
                    (case kind
                      :indieauth (list "Your own IndieAuth server at "
                                       [:strong (shared/domain endpoint)])
                      :github    (list "GitHub, as " [:strong user])
                      :mastodon  (list "Mastodon, as "
                                       [:strong (str "@" user "@"
                                                     (shared/domain instance))]))]]))
           choices)]))

(defn consent-form
  "The main content of the consent page: what `client-id` asks for (`scope`),
  approved by POSTing the signed `token` back.

  An external app's authorization request lands here once the site owner has
  signed in (see the auth namespace)."
  [client-id scope token]
  (list
    [:h1.page-title "Authorize app"]
    [:section.text
     [:p [:a {:href client-id} client-id] " asks to "
      (if scope
        (list "act on this site with the scopes " [:strong scope])
        "verify your identity")
      "."]
     [:form.consent-form {:method "post" :action "/auth/consent"}
      [:input {:type "hidden" :name "token" :value token}]
      [:button {:type "submit"} "Approve"]]
     [:p "Not you, or not this app? Just close this page."]]))

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
  metadata; noindex? keeps the page out of search engines."
  [title main conf & {:keys [reader? frontpage? h-feed? description path
                             responses noindex?]}]
  (str
    "<!doctype html>"
    (replicant/render
      [:html {:lang (:language conf)}
       [:head
        ;; dynamic
        (when title [:title title])
        (when description
          [:meta {:name "description" :content description}])
        (when noindex?
          [:meta {:name "robots" :content "noindex"}])
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
              ;; The bridged copy of this post, so a fediverse search for its
              ;; URL finds something to reply to. Posts only; the frontpage
              ;; has no bridged copy.
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
         ;; The frontpage and each tag page are h-feeds; their hidden mf2
         ;; comes from feed-meta. A tag page names itself in its own <h1>,
         ;; so it supplies no hidden name.
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
