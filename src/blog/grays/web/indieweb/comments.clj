(ns blog.grays.web.indieweb.comments
  "Native comments: written directly on a post's page by a signed-in visitor,
  rather than arriving as Webmentions from another site.

  IndieWeb content either way — the visitor is authenticated as their own
  website via IndieLogin (see the signin namespace) — so comments live in the
  :indieweb-dir alongside the mentions, under the file conventions of the store
  namespace. The :comment/auth attribute records how the author was
  authenticated (:indieauth today; other mechanisms can join later without
  restructuring). Entries are keyed by a generated id, a native comment having
  no remote URL; the id doubles as the comment's #comment-<id> anchor on the
  post page:

    comments/2026/some-post.edn    {id {:status .. :auth .. :content ..}}

  Moderation works exactly as it does for mentions: flip :status in your
  editor (:approved, :pending or :blocked) and the watcher does the rest."
  (:require [blog.grays.web.indieweb.store :as store])
  (:import [java.io File]
           [java.time Instant ZoneOffset]
           [java.time.format DateTimeFormatter]
           [java.util.concurrent ThreadLocalRandom]))

(def ^:private id-formatter
  (-> (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss")
      (.withZone ZoneOffset/UTC)))

(defn- generate-id
  "A fresh comment id: a UTC timestamp plus a short random suffix. Unique
  enough at this scale, it sorts chronologically and is safe to use in a DOM
  anchor and as an EDN map key."
  []
  (format "%s-%04x"
          (.format id-formatter (Instant/now))
          (.nextInt (ThreadLocalRandom/current) 0x10000)))

(defn- comments-dir
  [dir]
  (store/data-file dir "comments"))

(defn ensure-dir!
  "Create dir's comments subdirectory, so it exists to be watched."
  [dir]
  (.mkdirs ^File (comments-dir dir)))

(defn put-comment!
  "Record `comment` on the post at the permalink `path` under a fresh id,
  which is returned."
  [dir path comment]
  (let [id (generate-id)]
    (store/update-file! (store/entry-file (comments-dir dir) path) #(assoc % id comment))
    id))

(defn comments
  "The comments on the post at the permalink `path`, keyed by id."
  [dir path]
  (store/read-edn (store/entry-file (comments-dir dir) path)))

(defn entities
  "Every comment in `dir`, as db entity maps."
  [dir]
  (for [file (store/edn-files (comments-dir dir))
        :let [path (store/entry-path (comments-dir dir) file)]
        [id m] (store/read-edn file)]
    (assoc (store/qualify :comment m)
      :comment/id id
      :comment/target path)))

(comment
  (require '[blog.grays.web.service :as service])
  (def dir (:indieweb-dir service/dev-conf))

  (ensure-dir! dir)

  (put-comment! dir "/posts/2020/some-post"
                {:status      :approved
                 :auth        :indieauth
                 :received    "2026-07-17T09:12:03Z"
                 :published   "2026-07-17"
                 :author-name "Jane Doe"
                 :author-url  "https://example.com/"
                 :content     "Great post, but…"})

  (comments dir "/posts/2020/some-post")
  (entities dir)
  #_.)
