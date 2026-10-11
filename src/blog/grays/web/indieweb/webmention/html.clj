(ns blog.grays.web.indieweb.webmention.html
  "Reading the HTML of *other people's* pages: endpoint discovery, mention
  verification, reply contexts and Web sign-in are all built on reading the
  markup of strangers.

  A page is parsed once, with html-pieces, which reads malformed HTML as a
  browser does. Its microformats2 and rels are read from that tree by
  microformats-clj, to the parsing spec, with implied properties, nested
  microformats and every URL resolved. The classes of microformats1, e.g.
  hentry or vcard, are read too, as the spec's backcompat microformats.

  The parsed page, a doc, only ever goes back into this namespace; what the
  functions below hand back is plain data."
  (:require [clojure.string :as str]
            [dk.simongray.html-pieces :as html-pieces]
            [dk.simongray.microformats :as mf]
            [dk.simongray.wary-fetch.url :as fetch.url]
            [blog.grays.web.shared :as shared]))

(defn- elements
  "Every element of the Hiccup `tree`, in document order."
  [tree]
  (->> tree
       (mapcat #(tree-seq vector? (fn [[_ _ & children]] children) %))
       (filter vector?)))

(defn- base
  "What relative hrefs in the Hiccup `tree` of the page at `url` resolve
  against: its first <base> with an href, or else `url`."
  [tree url]
  (or (some (fn [[tag {:keys [href]}]]
              (when (and (= :base tag) href)
                (fetch.url/absolute-url url href)))
            (elements tree))
      url))

(defn parse
  "Parse the HTML string `s` of the page at `base-url` into a doc: its Hiccup
  :tree, its :mf2 and the :base its relative hrefs resolve against.

  `base-url` must be the final URL of the response, redirects included."
  [s base-url]
  (let [tree (html-pieces/parse s)]
    {:tree tree
     :mf2  (mf/parse tree base-url)
     :base (base tree base-url)}))

;;; Rels

(defn rel-hrefs
  "The resolved hrefs of every <link>/<a> in `doc` carrying `rel`, in document
  order. An empty href is the page itself, e.g. a Webmention endpoint."
  [doc rel]
  (get-in doc [:mf2 :rels rel]))

(defn rel-href
  "The resolved href of the first <link>/<a> in `doc` carrying `rel`; nil when
  none does."
  [doc rel]
  (first (rel-hrefs doc rel)))

(defn all-hrefs
  "Every resolved href that `doc` links to.

  What a Webmention source is verified against, and what provider-profile
  HTML (a Mastodon bio) is scanned with for a link back."
  [{:keys [tree base] :as doc}]
  (into #{}
        (keep (fn [[tag {:keys [href]}]]
                (when (and (#{:a :link} tag) href)
                  (fetch.url/absolute-url base href))))
        (elements tree)))

;;; Microformats2

(defn- items
  "Every item of the parsed `mf2`, depth first: each top-level item, then the
  items in its properties and its children."
  [mf2]
  (mapcat #(tree-seq map?
                     (fn [{:keys [properties children]}]
                       (concat (filter :type (mapcat val properties))
                               children))
                     %)
          (:items mf2)))

(defn- typed
  "The first item of the parsed `mf2` of the h-* `type`; nil when none."
  [mf2 type]
  (some #(when (some #{type} (:type %)) %) (items mf2)))

(defn- value
  "The plain value of the property value `v`: a string as it is, else the
  :value of an embedded item, an e- property or an img with alt text."
  [v]
  (if (map? v) (:value v) v))

(defn- property
  "The plain value of the first `k` property of `item`; nil when it has none."
  [item k]
  (some-> item (get-in [:properties k]) (first) (value) (not-empty)))

(defn- author
  "The author `v`, an h-card item or the plain value of a p-author, as
  {:name .. :url .. :photo ..} plain data; nil if it names nobody."
  [v]
  (not-empty
    (shared/compact
      (cond
        (map? v)                 {:name  (property v :name)
                                  :url   (property v :url)
                                  :photo (property v :photo)}
        (fetch.url/web-url? v)   {:url v}
        :else                    {:name (not-empty v)}))))

(defn card
  "The first h-card of `doc`, as {:name .. :url .. :photo ..} plain data; nil
  when the page marks none up. An approximation of mf2's representative
  h-card: on the homepages we read, the first h-card is the site's author."
  [doc]
  (some-> (typed (:mf2 doc) "h-card") (author)))

(defn- title
  "The text of the <title> element of the Hiccup `tree`; nil without one."
  [tree]
  (some->> (elements tree)
           (some (fn [[tag _ & children]]
                   (when (= :title tag)
                     (apply str (filter string? children)))))
           (str/trim)
           (not-empty)))

(def kinds
  "The mention kinds, in precedence order: :reply wins when a source marks
  the same target with several properties."
  [:reply :like :repost :bookmark])

(def kind->property
  "The mf2 property by which an h-entry marks up each kind of mention it
  makes."
  {:reply    :in-reply-to
   :like     :like-of
   :repost   :repost-of
   :bookmark :bookmark-of})

(defn entry
  "What we can read out of `doc`, as plain data:

    :title      the name of its first h-entry, or else the <title> element
    :url        the url it claims for itself, not necessarily where we got it
    :author     {:name .. :url .. :photo ..}, when the h-entry names one
    :published  the h-entry's published, when it marks one up
    :content    the text of its content, or failing that its summary
    :links      every URL the page links to
    :reply      }
    :like       } the URLs that the page's microformats give under each
    :repost     } kind of mention
    :bookmark   }

  The links are read from the whole page rather than from the h-entry: a
  Webmention only requires that the source link its target *somewhere*.
  All URLs are resolved."
  [{:keys [tree mf2] :as doc}]
  (let [item (typed mf2 "h-entry")]
    (into {:title     (or (property item :name) (title tree))
           :url       (property item :url)
           :author    (some-> item (get-in [:properties :author]) (first) (author))
           :published (property item :published)
           :content   (or (property item :content) (property item :summary))
           :links     (all-hrefs doc)}
          (for [[kind k] kind->property]
            [kind (into #{}
                        (comp (mapcat #(get-in % [:properties k]))
                              (keep value))
                        (items mf2))]))))

(comment
  ;; The point of the plain-data return: this is all testable from a string,
  ;; without an HTTP fetch in sight.
  (entry (parse "<article class='h-entry'>
                   <a class='p-author h-card' href='/me'>
                     <span class='p-name'>Jane Doe</span></a>
                   <h1 class='p-name'>The Real Post Title</h1>
                   <a class='u-like-of' href='/posts/2020/some-post'>♥</a>
                 </article>"
                "https://example.com/"))

  ;; A <data value> p-name has no text; the title is in the attribute.
  (:title (entry (parse "<div class='h-entry'>
                           <data class='p-name' value='Slept 8 hours'></data>
                         </div>"
                        "https://example.com/")))

  (rel-href (parse "<link rel='webmention' href='/wm'>" "https://example.com/")
            "webmention")
  #_.)
