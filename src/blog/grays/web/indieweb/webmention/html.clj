(ns blog.grays.web.indieweb.webmention.html
  "Reading the HTML of *other people's* pages.

  Everywhere else, HTML is something we emit: our own markup is hiccup (see
  component.cljc) and we never read it back. This namespace is the exception,
  and the only place in the codebase where a DOM exists, because the IndieWeb
  protocols are all built on reading the markup of strangers:

    - endpoint discovery reads a rel=webmention link out of a target page,
    - mention verification reads the source page that mentioned us,
    - reply contexts read the page that one of our posts replies to.

  Such HTML arrives as a string from an HTTP fetch, is produced by software we
  do not control, and is routinely malformed, so it takes a tolerant HTML5
  tree builder to be read at all. Hence jsoup, which also resolves relative
  hrefs against the document base (`abs:href`).

  Of microformats2 we handle only what those three features consume: p-name,
  u-url, p-author (and its p-name/u-url/u-photo), dt-published, e-content (or
  p-summary), and the u-in-reply-to/u-like-of/u-repost-of/u-bookmark-of verbs.
  This is a deliberate approximation and not an mf2 parser: no
  value-class-pattern, no implied properties, no nested h-cite, and e-content
  is read as text rather than as markup. The two rules of the spec that a plain
  CSS selector gets wrong, both of which were bugs here once, are respected in
  `property` and `property-value`.

  Jsoup types stay inside this namespace: `parse` yields a document, and
  `endpoint-href`/`entry` reduce it to plain data."
  (:require [clojure.string :as str]
            [blog.grays.web.shared :as shared])
  (:import [org.jsoup Jsoup]))

(defn parse
  "Parse the HTML string `s` of the page at `base-url` into a jsoup document.

  `base-url` must be the final URL of the response, redirects included: it is
  what relative hrefs resolve against."
  [s base-url]
  (Jsoup/parse s base-url))

;;; Endpoint discovery (rel parsing, not microformats)

(defn endpoint-href
  "The href of the first <link>/<a> with rel~=webmention in the jsoup `doc`.

  Returned as authored rather than absolutised: an empty href means the page
  itself, which the caller must special-case when resolving."
  [doc]
  (->> (.select doc "link[rel][href], a[rel][href]")
       (some (fn [el]
               (when (->> (str/split (.attr el "rel") #"\s+")
                          (some #{"webmention"}))
                 (.attr el "href"))))))

;;; Microformats2

(defn- mf-root?
  "Is the jsoup `el` a microformats root, i.e. does it carry an h-* class?"
  [el]
  (boolean (some #(str/starts-with? % "h-") (.classNames el))))

(defn- property
  "The first element under the jsoup `root` matching the mf2 `selector` that is
  a property of `root` itself. A match nested inside another microformat root,
  the p-name of a p-author h-card say, is a property of *that* root, so it
  is skipped; a plain CSS descendant selector would wrongly pick it up."
  [root selector]
  (->> (.select root selector)
       (remove (fn [el]
                 (loop [parent (.parent el)]
                   (cond
                     (nil? parent)            false
                     (identical? parent root) false
                     (mf-root? parent)        true
                     :else                    (recur (.parent parent))))))
       (first)))

(defn- property-value
  "The value of the jsoup mf2 property element `el`: its text, except in the
  value-attribute forms of the spec, whose text is empty by design."
  [el]
  (when el
    (not-empty
      (or (case (.tagName el)
            ("data" "input") (not-empty (.attr el "value"))
            "abbr"           (not-empty (.attr el "title"))
            ("img" "area")   (not-empty (.attr el "alt"))
            nil)
          (.text el)))))

(defn- hrefs
  "The absolutised hrefs of the elements in the jsoup `doc` matching `selector`."
  [doc selector]
  (into #{} (map #(.attr % "abs:href")) (.select doc selector)))

(defn- url
  "The absolutised u-url of the jsoup microformat root `el`; nil when it marks
  none up."
  [el]
  ;; Only an <a>/<link> with an href can carry a u-url. An h-entry states its
  ;; permalink this way, and so does an h-card.
  (some-> (property el "a.u-url[href], link.u-url[href]")
          (.attr "abs:href")
          (not-empty)))

(defn- author
  "The h-card `el` of an h-entry's p-author, as plain data; nil if it names
  nobody."
  [el]
  (not-empty
    (shared/compact
      {:name  (or (property-value (property el ".p-name"))
                  (not-empty (.text el)))
       ;; An h-card is usually the <a> to its own profile; failing that, it
       ;; marks the profile up as a u-url within.
       :url   (or (not-empty (.attr el "abs:href"))
                  (url el))
       :photo (some-> (property el "img.u-photo")
                      (.attr "abs:src")
                      (not-empty))})))

(defn card
  "The first h-card of the jsoup `doc`, as {:name .. :url .. :photo ..} plain
  data; nil when the page marks none up. A deliberate approximation of mf2's
  representative h-card, in the spirit of the rest of this namespace: on the
  homepages we read it from, the first h-card is the site's author."
  [doc]
  (some-> (.selectFirst doc ".h-card") (author)))

(def kind->class
  "The mf2 class by which an h-entry marks up each kind of mention it makes."
  {:reply    "u-in-reply-to"
   :like     "u-like-of"
   :repost   "u-repost-of"
   :bookmark "u-bookmark-of"})

(defn entry
  "What we can read out of the jsoup `doc`, as plain data:

    :title      the p-name of its first h-entry, or else the <title> element
    :url        the u-url it claims for itself, not necessarily where we got it
    :author     {:name .. :url .. :photo ..}, when the h-entry marks one up
    :published  the h-entry's dt-published, when it marks one up
    :content    the text of its e-content, or failing that its p-summary
    :links      every URL the page links to
    :reply      }
    :like       } the URLs it links to under each kind of mention
    :repost     }
    :bookmark   }

  The link sets are read from the whole page rather than from the h-entry: a
  Webmention only requires that the source link its target *somewhere*. All
  URLs are absolutised against the document base."
  [doc]
  (let [el (.selectFirst doc ".h-entry")]
    (into {:title     (or (some-> el (property ".p-name") (property-value))
                          (some-> (.selectFirst doc "title") (.text) (not-empty)))
           :url       (some-> el (url))
           :author    (some-> el (property ".p-author") (author))
           :published (some-> el
                              (property ".dt-published[datetime]")
                              (.attr "datetime")
                              (not-empty))
           :content   (or (some-> el (property ".e-content") (property-value))
                          (some-> el (property ".p-summary") (property-value)))
           :links     (hrefs doc "a[href], link[href]")}
          (for [[kind class] kind->class]
            [kind (hrefs doc (str "a." class "[href], link." class "[href]"))]))))

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

  (endpoint-href (parse "<link rel='webmention' href='/wm'>" "https://example.com/"))
  #_.)
