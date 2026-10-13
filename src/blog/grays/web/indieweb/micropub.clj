(ns blog.grays.web.indieweb.micropub
  "Our Micropub endpoint (https://micropub.spec.indieweb.org/), made with
  micropub-clj, which checks the tokens, reads the requests and answers the
  clients. What's ours is the mapping between Micropub entries and post
  files.

  Create, update and delete each reduce to a file operation on the
  :posts-dir; the watcher then syncs the change into the content db like any
  hand-written edit, including Webmention/WebSub notifications. Undelete is
  not supported. Bearer tokens are verified locally against the tokens our
  own IndieAuth server issued (tokens.edn; see the auth namespace)."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [sluj.core :refer [sluj]]
            [taoensso.telemere :as tel]
            [dk.simongray.html-pieces :as html]
            [dk.simongray.micropub.server :as server]
            [blog.grays.web.content :as content]
            [blog.grays.web.db :as db]
            [blog.grays.web.http :as http]
            [blog.grays.web.indieweb.auth :as auth]
            [blog.grays.web.shared :as shared])
  (:import [java.time LocalDate]))

;;; Properties

(defn content-value
  "The markdown for an mf2 `content` value: its :html as Markdown when a map,
  else the value itself. Our Markdown renderer shows raw HTML blocks as an
  unknown type, so HTML never reaches a post file."
  [content]
  (if (map? content)
    (some-> (:html content) html/markdown)
    content))

(def property->attr
  "The mf2 properties of an entry that the frontmatter holds, mapped to the
  attribute holding each. The content is the body, and photos go into it as
  Markdown images; any other property is ignored, as Micropub 3.3.5 allows."
  {:name        :title
   :published   :date
   :category    :tags
   :in-reply-to :reply-to
   :rsvp        :rsvp
   :like-of     :like-of
   :repost-of   :repost-of
   :bookmark-of :bookmark-of
   :syndication :syndication
   :location    :location})

(def canonical
  "How the value of an attribute is written where it isn't as given: a
  published date and time as its date, and an rsvp in lower case, so the
  frontmatter and the rendered p-rsvp value stay canonical."
  {:date #(re-find #"^\d{4}-\d{2}-\d{2}" %)
   :rsvp str/lower-case})

(def list-attrs
  "Frontmatter attributes holding several values: how to split the file form
  and how to rejoin it. An attribute absent here is a scalar."
  {:tags        {:split #",\s*" :join ", "}
   :syndication {:split #"\s+"  :join " "}})

(defn- split-values
  "The values of `attr` in `frontmatter` as a vector, or nil when unset."
  [frontmatter attr]
  (when-let [v (get frontmatter attr)]
    (if-let [{:keys [split]} (list-attrs attr)]
      (vec (str/split v split))
      [v])))

(defn- put-values
  "Set `attr` in `frontmatter` to `values`, joining a list attribute and taking
  the first of a scalar; an empty result dissocs the attribute entirely."
  [frontmatter attr values]
  (let [values (remove str/blank? values)]
    (if (empty? values)
      (dissoc frontmatter attr)
      (assoc frontmatter attr
             (if-let [{:keys [join]} (list-attrs attr)]
               (str/join join values)
               (first values))))))

(defn post->entry
  "The h-entry of a post file's `[frontmatter body]`: the body as its content,
  and the attributes of property->attr as their properties."
  [[frontmatter body]]
  {:type       ["h-entry"]
   :properties (into (if (str/blank? body) {} {:content [body]})
                     (for [[prop attr] property->attr
                           :let  [values (split-values frontmatter attr)]
                           :when values]
                       [prop values]))})

(defn- photo-url
  "The URL of an mf2 `photo`: the photo itself, or the :value of a map."
  [photo]
  (if (map? photo) (:value photo) photo))

(defn- photo-markdown
  "The Markdown image of an mf2 `photo`: a URL, or a map of its :value and
  :alt."
  [photo]
  (str "![" (when (map? photo) (:alt photo)) "](" (photo-url photo) ")"))

(defn entry->post
  "The `[frontmatter body]` of a post file for the `entry`, written over the
  `frontmatter` of the file so that it keeps the attributes that
  property->attr lacks. The body is the content, followed by any photos.
  Only text values count; a nested object such as an h-card is ignored."
  [frontmatter {:keys [properties] :as entry}]
  [(reduce-kv (fn [fm prop attr]
                (put-values fm attr (->> (get properties prop)
                                         (filter string?)
                                         (keep (canonical attr identity)))))
              frontmatter
              property->attr)
   (->> (concat (keep content-value (:content properties))
                (map photo-markdown (filter #(or (string? %) (string? (:value %)))
                                            (:photo properties))))
        (filter string?)
        (remove str/blank?)
        (str/join "\n\n"))])

;;; Files

(defn- frontmatter-block
  "The YAML frontmatter block for the `[key value]` pairs `kvs`, one `key: value`
  line each; a nil value is skipped and its key omitted."
  [kvs]
  (str "---\n"
       (str/join "\n" (for [[k v] kvs :when (some? v)]
                        (str (name k) ": " v)))
       "\n---\n\n"))

(defn- ->frontmatter
  "The YAML frontmatter block of `post`: its date, title, slug, tags, any
  response verb (shared/response-verb-attrs), its rsvp, syndication and
  location, each written out only when present."
  [post]
  (frontmatter-block (for [k (concat [:date :title :slug :tags]
                                     shared/response-verb-attrs
                                     [:rsvp :syndication :location])]
                       [k (get post k)])))

(defn- parse-file
  "The [frontmatter body] of the markdown `file`, split the same way the db
  ingest splits it (content/split-frontmatter), so the in-memory view matches
  the db's."
  [file]
  (content/split-frontmatter (slurp file)))

(defn write-post!
  "Write the `[frontmatter body]` pair back to `file` as markdown."
  [file [frontmatter body]]
  (spit file (str (frontmatter-block frontmatter) body "\n")))

(defn url->year+slug
  "The [year slug] pair of the post permalink `url`; empty when it does not
  have permalink shape."
  [url]
  (rest (re-find #"/posts/(\d{4})/([^/]+?)/?$" (str url))))

(defn post-file
  "The file of the post at the permalink `url` in `db`, or nil."
  [db url]
  (let [[year slug] (url->year+slug url)]
    (:file (db/find-post db year slug))))

;;; Creating

(defn derive-slug
  "A URL slug for a new post based on its :slug, :title, :content, or the target
  it responds to."
  [{:keys [slug title content] :as post}]
  (or (not-empty (str slug))
      (some-> title sluj not-empty)
      ;; untitled notes: slugify the first few words of the content
      (some->> (str/split (str content) #"\s+")
               (take 5)
               (str/join " ")
               (sluj)
               (not-empty))
      ;; a response with no words of its own (a like, a bookmark): name it after
      ;; what it responds to.
      (some-> (some post shared/response-verb-attrs)
              (str/replace #"^https?://" "")
              (sluj)
              (not-empty))))

(defn unique-slug
  "Make `slug` unique among the posts of `year` in `db` and the files in
  `posts-dir`. A new post's file is named after its slug, whatever the
  year, and the db only has a file once the watcher has synced it."
  [db posts-dir year slug]
  (->> (cons slug (map #(str slug "-" %) (iterate inc 2)))
       (remove #(or (db/find-post db year %)
                    (.exists (io/file posts-dir (str % ".md")))))
       (first)))

(defn create!
  "Write the post of the create `action` into the :posts-dir of `conf` as a
  markdown file, and return its eventual permalink with a 202: the post only
  goes live once the file watcher has synced it. Anything but an h-entry
  with content or a response verb is refused."
  [db {:keys [posts-dir url] :as conf} {:keys [type properties commands] :as action}]
  (let [[frontmatter body] (entry->post {} action)
        ;; the slug comes from the content alone, not the photos in the body
        post               (assoc frontmatter :content (some-> (first (:content properties))
                                                               content-value))]
    ;; A like/repost/bookmark carries no content of its own, only a verb.
    (if-let [slug (and (= ["h-entry"] type)
                       (or (not (str/blank? body))
                           (some post shared/response-verb-attrs))
                       (or (derive-slug (assoc post :slug (first (:mp-slug commands))))
                           ;; a photo with no words of its own: name it after
                           ;; the file
                           (some-> (first (:photo properties)) photo-url content/file-slug)))]
      (let [date (or (:date frontmatter) (str (LocalDate/now)))
            year (subs date 0 4)
            slug (unique-slug db posts-dir year slug)
            file (io/file posts-dir (str slug ".md"))]
        (spit file (str (->frontmatter (assoc frontmatter :date date :slug slug))
                        body "\n"))
        (tel/log! {:level :info
                   :id    ::post-created
                   :data  {:file (str file)}
                   :msg   (str "Micropub post created: " file)})
        {:url (str url (shared/post-href year slug)) :status 202})
      {:error {:code   "invalid_request"
               :reason "Could not create a post from the request."}})))

;;; Reading, updating and deleting

(defn source
  "The entry of the post at the :url of the `request`, read from its file
  rather than the db, whose title may come from the post's first heading.
  Nil when there's no post there."
  [db {:keys [url] :as request}]
  (some-> (post-file db url) parse-file post->entry))

(defn- year-of
  "The year of the date in `frontmatter`, or nil."
  [frontmatter]
  (some-> (:date frontmatter) (subs 0 4)))

(defn update!
  "Rewrite the file of the post at the :url of the update `action` with its
  :entry, which micropub-clj made from our source. The year and the slug fix
  the permalink, so an update that would move the post to another year is
  refused, since the old URL would 404. The slug isn't a property, so it
  stays."
  [db {:keys [url entry] :as action}]
  (let [file                (post-file db url)
        [frontmatter]       (parse-file file)
        [frontmatter' body] (entry->post frontmatter entry)]
    (if (not= (year-of frontmatter) (year-of frontmatter'))
      {:error {:code   "invalid_request"
               :reason "An update can't move a post to another year."}}
      (do
        (write-post! file [frontmatter' body])
        (tel/log! {:level :info
                   :id    ::post-updated
                   :data  {:file file}
                   :msg   (str "Micropub post updated: " file)})
        nil))))

(defn delete!
  "Remove the file of the post at the :url of the delete `action`, so the
  watcher retracts it and re-sends its Webmentions to withdraw any federated
  copies. This is a hard delete; undelete is not supported."
  [db {:keys [url] :as action}]
  (if-let [file (post-file db url)]
    (do
      (io/delete-file file)
      (tel/log! {:level :info
                 :id    ::post-deleted
                 :data  {:file file}
                 :msg   (str "Micropub post deleted: " file)})
      nil)
    {:error {:code "invalid_request" :reason "No post found at that URL."}}))

;;; Media

(def content-type->ext
  "Image content types the media endpoint accepts, mapped to the extension to
  store them under; the fallback when an upload's filename carries no usable
  extension. Values are content/img-ext members."
  {"image/jpeg"    "jpg"
   "image/png"     "png"
   "image/gif"     "gif"
   "image/svg+xml" "svg"})

(defn store-asset!
  "Store `x`, a file or bytes, under the posts assets/ dir of `conf` as a
  file named after `slug` with the extension `ext`, and return its served
  URL. The file is served statically, so it is live at once."
  [{:keys [posts-dir url] :as conf} slug ext x]
  (let [dir   (io/file posts-dir shared/assets-dir)
        base  (str (LocalDate/now) "-" (or slug "photo"))
        ;; Unique within assets/ by numbering the basename, as
        ;; unique-slug does for posts within a year.
        fname (->> (cons base (map #(str base "-" %) (iterate inc 2)))
                   (map #(str % "." ext))
                   (remove #(.exists (io/file dir %)))
                   (first))
        dest  (io/file dir fname)]
    (io/make-parents dest)
    (io/copy x dest)
    (str url shared/assets-path "/" fname)))

(defn upload!
  "Store the uploaded `file` under the posts assets/ dir of `conf` and return
  its served URL, for the media endpoint or the photo of a multipart create.
  A file that isn't an image is refused."
  [conf {:keys [filename content-type tempfile] :as file}]
  ;; The filename's own extension, else the declared content type.
  (let [ext (or (some-> filename content/file-ext str/lower-case content/img-ext)
                (some-> content-type str/lower-case content-type->ext))]
    (if (and tempfile ext)
      (let [served (store-asset! conf (content/file-slug filename) ext tempfile)]
        (tel/log! {:level :info
                   :id    ::media-uploaded
                   :data  {:url served}
                   :msg   (str "Micropub media uploaded: " served)})
        served)
      {:error {:code "invalid_request" :reason "Expected an image file."}})))

(defn copy-photo!
  "The `photo`, pointing at a copy in the posts assets/ dir of `conf` when
  its URL is on another site, or as it was when the copy fails. Our CSP is
  default-src 'self', so a browser won't show an image from another site,
  and hotlinking one would hand that site every reader's IP."
  [{:keys [url] :as conf} photo]
  (let [src (photo-url photo)]
    (if (or (not (string? src)) (str/starts-with? src (str url "/")))
      photo
      (try
        ;; a photo may well be larger than an avatar
        (let [{:keys [bytes ext]} (or (http/get-image! src (* 10 1024 1024))
                                      (throw (ex-info "No image there" {})))
              copy                (store-asset! conf (content/file-slug src) ext bytes)]
          (if (map? photo) (assoc photo :value copy) copy))
        (catch Exception e
          (tel/log! {:level :info
                     :id    ::photo-error
                     :data  {:url src}
                     :msg   (str "Could not copy the photo " src ": " (ex-message e))})
          photo)))))

(defn copy-photos!
  "The `entry` with each of its photos copied by copy-photo! under `conf`."
  [conf entry]
  (cond-> entry
    (get-in entry [:properties :photo])
    (update-in [:properties :photo] #(mapv (partial copy-photo! conf) %))))

;;; Endpoints

(def config
  "Our answer to q=config, besides the media endpoint and the queries that
  micropub-clj adds: the post types a client can offer a composer for, and
  no syndication targets."
  {:syndicate-to []
   :post-types   [{:type "note" :name "Note"}
                  {:type "article" :name "Article"}
                  {:type "reply" :name "Reply"}
                  {:type "rsvp" :name "RSVP"}
                  {:type "like" :name "Like"}
                  {:type "repost" :name "Repost"}
                  {:type "bookmark" :name "Bookmark"}]})

(defn opts
  "The options of micropub-clj's handlers for the posts in `db` and the site
  of `conf`. A token must belong to this site: a valid token for someone
  else's site is not a valid token for ours."
  [db {:keys [url media-endpoint] :as conf}]
  {:me-pred        #(= (shared/domain %) (shared/domain url))
   :tokens         (:tokens (auth/server-opts conf))
   :media-endpoint media-endpoint
   :create!        #(create! db conf (copy-photos! conf %))
   :source         #(source db %)
   :update!        #(update! db (update % :entry (partial copy-photos! conf)))
   :delete!        #(delete! db %)
   :upload!        #(upload! conf %)
   :config         config
   ;; A Micropub extension: the known tag slugs, so a client can offer
   ;; autocompletion instead of a blank category field.
   :queries        {:category (fn [_]
                                {:categories (vec (map key (db/tag-counts db)))})}})

(defn endpoint
  "The Micropub endpoint for `db` and `conf`, as a Ring handler."
  [db conf]
  (server/handler (opts db conf)))

(defn media-endpoint
  "The Micropub media endpoint for `db` and `conf`, as a Ring handler."
  [db conf]
  (server/media-handler (opts db conf)))
