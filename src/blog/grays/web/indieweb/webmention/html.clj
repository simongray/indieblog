(ns blog.grays.web.indieweb.webmention.html
  "Reading the HTML of *other people's* pages, for what we don't hand to
  webmention-clj: reply contexts, and Web sign-in's rels and links back.

  A page is parsed once, with html-pieces, which reads malformed HTML as a
  browser does. Its microformats2 and rels are read from that tree by
  microformats-clj, to the parsing spec, with implied properties, nested
  microformats and every URL resolved. The classes of microformats1, e.g.
  hentry or vcard, are read too, as the spec's backcompat microformats.

  The parsed page, a doc, is plain data: its tree, its microformats, and
  what its relative hrefs resolve against."
  (:require [clojure.string :as str]
            [dk.simongray.html-pieces :as html-pieces]
            [dk.simongray.microformats :as mf]
            [dk.simongray.wary-fetch.url :as fetch.url]))

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
     ;; The wiki's proposed rules only fill in the date and author that an
     ;; older WordPress theme's hentry lacks, which is all we show of it.
     :mf2  (mf/parse tree base-url {:proposed-backcompat? true})
     :base (base tree base-url)}))

(defn title
  "The text of the <title> element of `doc`; nil without one."
  [{:keys [tree] :as doc}]
  (some->> (elements tree)
           (some (fn [[tag _ & children]]
                   (when (= :title tag)
                     (apply str (filter string? children)))))
           (str/trim)
           (not-empty)))

;;; Rels and links

(defn rel-hrefs
  "The resolved hrefs of every <link>/<a> in `doc` carrying `rel`, in document
  order. An empty href is the page itself."
  [doc rel]
  (get-in doc [:mf2 :rels rel]))

(defn rel-href
  "The resolved href of the first <link>/<a> in `doc` carrying `rel`; nil when
  none does."
  [doc rel]
  (first (rel-hrefs doc rel)))

(defn all-hrefs
  "Every resolved href that `doc` links to, e.g. what provider-profile HTML
  (a Mastodon bio) is scanned with for a link back."
  [{:keys [tree base] :as doc}]
  (into #{}
        (keep (fn [[tag {:keys [href]}]]
                (when (and (#{:a :link} tag) href)
                  (fetch.url/absolute-url base href))))
        (elements tree)))

(comment
  (title (parse "<title> Some Blog </title>" "https://example.com/"))

  (rel-hrefs (parse "<a rel='me' href='/about'>me</a>" "https://example.com/")
             "me")
  #_.)
