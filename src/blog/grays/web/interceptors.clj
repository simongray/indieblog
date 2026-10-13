(ns blog.grays.web.interceptors
  "Pedestal interceptors and handlers backing the web service routes."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jsonista.core :as json]
            [io.pedestal.interceptor :as ic]
            [io.pedestal.http.response :as response]
            [taoensso.telemere :as tel]
            [dk.simongray.indieauth :as indieauth]
            [dk.simongray.indieauth.server :as server]
            [dk.simongray.wary-fetch.url :as fetch.url]
            [dk.simongray.websub :as websub]
            [blog.grays.web.feed :as feed]
            [blog.grays.web.component :as c]
            [blog.grays.web.indieweb.comments :as comments]
            [blog.grays.web.db :as db]
            [blog.grays.web.shared :as shared]
            [blog.grays.web.indieweb.auth :as auth]
            [blog.grays.web.indieweb.signin :as signin]
            [blog.grays.web.indieweb.webmention :as webmention]
            [blog.grays.web.indieweb.micropub :as micropub])
  (:import [java.time Duration Instant LocalDate]
           [java.time.temporal ChronoUnit]))

(defn attach-conf
  "Attaches `conf` and the content db to the request; should
  precede handlers."
  [conf]
  (ic/interceptor
    {:name  ::attach-conf
     :enter (fn [ctx] (update ctx :request assoc :conf conf :db db/db))}))

(defn html-response
  ([body]
   (html-response 200 body))
  ([status body]
   {:status  status
    :headers {"Content-Type" "text/html;charset=utf-8"}
    :body    body}))

(defn text-response
  [status body]
  {:status  status
   :headers {"Content-Type" "text/plain"}
   :body    body})

(def not-found
  "Renders the styled 404 page whenever no handler produced a response;
  Pedestal's own not-found interceptor would return plain text. Must come
  after `attach-conf` so that :conf is present on the request."
  (ic/interceptor
    {:name  ::not-found
     :leave (fn [{:keys [request] :as ctx}]
              (if (and (not (response/response? (:response ctx)))
                       (response/response-expected? ctx))
                (let [{:keys [conf uri]} request]
                  (assoc ctx :response
                         (html-response 404 (c/page (str "Not found — " (:name conf))
                                                    (c/not-found uri)
                                                    conf
                                                    :reader? true))))
                ctx))}))

(def dynamic-content-types
  "Content-type prefixes of dynamically generated responses; never cached."
  #{"text/html" "text/markdown" "application/rss+xml" "application/xml"
    "application/json"})

(defn- cache-control-value
  "The Cache-Control header value for a `request`/`response` pair: dynamic
  content is revalidated, static files are cached. The stylesheet gets a year
  since its URL carries a content hash (:css-version), except in development
  where it is edited live."
  [{:keys [uri conf]} {:keys [status headers]}]
  (let [content-type (get headers "Content-Type" "")]
    (cond
      (or (not= 200 status)
          (some #(str/starts-with? content-type %) dynamic-content-types))
      "no-cache"

      (str/starts-with? uri "/css/")
      (if (:development conf)
        "no-cache"
        "public, max-age=31536000, immutable")

      :else
      "public, max-age=604800")))

(def cache-control
  "Attaches a Cache-Control header to any response that doesn't set its own."
  (ic/interceptor
    {:name  ::cache-control
     :leave (fn [{:keys [request response] :as ctx}]
              (if (and response (nil? (get-in response [:headers "Cache-Control"])))
                (assoc-in ctx [:response :headers "Cache-Control"]
                          (cache-control-value request response))
                ctx))}))

(defn frontpage
  "Renders the frontpage: the h-feed of article snippets, with the latest
  response posts in the strip below."
  [{:keys [conf db] :as req}]
  (let [{articles false responses true} (group-by shared/response-post?
                                                  (db/posts db))]
    (html-response
      (c/page (:name conf)
              (c/articles articles conf)
              conf
              :frontpage? true
              :responses (take 3 responses)
              :description (shared/stringify (:tagline conf))
              :path "/"))))

(defn single-post
  "Renders the post at `year`/`slug` as HTML or raw markdown, depending on
  content negotiation or a .md suffix on `slug`; a deleted post answers 410
  Gone, and any other miss is logged and left for the `not-found` interceptor
  to render."
  [{:keys [conf db path-params query-params accept] :as req}]
  (let [{:keys [year slug]} path-params
        markdown? (or (str/ends-with? slug ".md")
                      (= "text/markdown" (:field accept)))
        slug      (str/replace slug #"\.md$" "")
        path      (shared/post-href year slug)
        ;; A completed Web sign-in returns here with a signed token; only a
        ;; valid comment token minted for this very post shows the form.
        signed-in (when-let [token (:signed-in query-params)]
                    (let [m (signin/read-comment-token token)]
                      (when (= path (:path m))
                        {:me (:me m) :token token})))]
    (if-let [post (db/find-post db year slug)]
      (if markdown?
        {:status  200
         :headers {"Content-Type" "text/markdown;charset=utf-8"
                   "Vary"         "Accept"}
         :body    (:content post)}
        (-> (c/page (str (c/post-title post) " — " (:name conf))
                    (c/article post (rand-nth c/palette) conf
                               :mentions (db/verified-mentions db path)
                               :comments (db/approved-comments db path)
                               :reply-context (webmention/reply-context db conf (:reply-to post))
                               :signed-in signed-in)
                    conf
                    :reader? true
                    :description (c/post-description post)
                    :path path)
            (html-response)
            (assoc-in [:headers "Vary"] "Accept")))
      ;; A permalink that misses but has delivery records is a post that
      ;; once existed: 410 tells both humans and the Webmention deletion
      ;; flow that it is gone on purpose.
      (if (seq (db/delivery-targets db path))
        (html-response 410 (c/page (str "Gone — " (:name conf))
                                   (c/gone path)
                                   conf
                                   :reader? true))
        (tel/log! {:level :warn
                   :id    ::post-not-found
                   :data  {:year year :slug slug}
                   :msg   (str "No post found for " year "/" slug)})))))

(defn standalone-page
  "Renders the standalone page named by the request path (one of
  shared/page-slugs); a page whose markdown file is absent is left for the
  not-found interceptor to render."
  [{:keys [conf db uri] :as req}]
  (let [slug (subs uri 1)]
    (when-let [page (db/find-page db slug)]
      (html-response
        (c/page (str (c/post-title page) " — " (:name conf))
                (c/plain page)
                conf
                :reader? true
                :description (c/post-description page)
                :path uri)))))

(defn rss-feed
  "Renders the RSS feed; the Link header advertises the WebSub hub and the
  canonical (self) feed URL for hub discovery."
  [{:keys [conf db] :as req}]
  (let [{:keys [url websub-hub]} conf]
    {:status  200
     :headers (cond-> {"Content-Type" "application/rss+xml"}
                websub-hub
                (assoc "Link" (websub/link-header [websub-hub]
                                                  (str url shared/feed-path))))
     :body    (feed/xml conf (->> (db/posts db)
                                  (remove shared/response-post?)
                                  (take 10)))}))

(defn tag-index
  "Renders the tag index: every tag carried by a post, with its post count."
  [{:keys [conf db] :as req}]
  (html-response
    (c/page (str "Tags — " (:name conf))
            (c/tag-index (db/tag-counts db))
            conf
            :reader? true
            :description "Every tag used on the blog"
            :path shared/tags-path)))

(defn search
  "Renders the search page: the form, and the posts matching the q query-param,
  best first. Kept out of search engines, which have their own."
  [{:keys [conf db query-params] :as req}]
  (let [q       (not-empty (str/trim (str (:q query-params))))
        posts   (when q (db/search-posts db q))
        results (map (juxt identity #(db/excerpt db % q)) posts)]
    (html-response
      (c/page (str (when q (str q " — ")) "Search — " (:name conf))
              (c/search-results q results (:typos (meta posts)))
              conf
              :reader? true
              :noindex? true
              :description (str "Search " (:name conf))))))

(defn tagged
  "Renders the h-feed of posts tagged with the `tag` path-param; an unused tag
  matches nothing and is left for the not-found interceptor to render."
  [{:keys [conf db path-params] :as req}]
  (let [{:keys [tag]} path-params]
    (when-let [posts (seq (db/tagged-posts db tag))]
      (html-response
        (c/page (str "#" tag " — " (:name conf))
                (c/tagged tag posts conf)
                conf
                :h-feed? true
                :description (str "Posts tagged #" tag)
                :path (str "/tags/" tag))))))

(defn tag-feed
  "Renders the RSS feed of articles tagged with the `tag` path-param; an unused
  tag matches nothing and is left for the not-found interceptor.

  No WebSub Link header: the hub is pinged only for the main feed, so a per-tag
  feed must not advertise a hub that will never notify it."
  [{:keys [conf db path-params] :as req}]
  (let [{:keys [tag]}   path-params
        tagged          (db/tagged-posts db tag)]
    (when (seq tagged)
      {:status  200
       :headers {"Content-Type" "application/rss+xml"}
       :body    (feed/xml conf (->> tagged (remove shared/response-post?) (take 10))
                          :title       (str (:name conf) ": #" tag)
                          :description (str "Posts tagged #" tag)
                          :feed-url    (str (:url conf) "/tags/" tag "/feed"))})))

(defn webmention
  "Accepts incoming Webmentions at our endpoint (see the webmention
  namespace); verification is asynchronous, so a 202 only means the request
  was well-formed and targets an existing post. The on-page mention form
  POSTs here too: a browser (told apart by its Accept header) gets a
  redirect back to the post or a styled 400 page."
  [{:keys [conf db form-params headers] :as req}]
  (let [{:keys [source target]} form-params
        browser?                (str/includes? (get headers "accept" "") "text/html")
        {:keys [status] :as response} ((webmention/endpoint db conf) req)]
    (cond
      (not browser?) response
      (= 202 status) {:status  303
                      :headers {"Location" (str (webmention/target-path db (:url conf)
                                                                        target)
                                                "#comments")}}
      :else          (html-response 400 (c/page (str "Invalid Webmention — " (:name conf))
                                                (c/invalid-mention source)
                                                conf
                                                :reader? true)))))

(defn- sign-in-failure
  [conf]
  (html-response 400 (c/page (str "Sign-in failed — " (:name conf))
                             (c/sign-in-failed)
                             conf
                             :reader? true)))

(defn sign-in
  "Begins a visitor's Web sign-in: their claimed URL and the post they came
  from are validated, then they are sent off to the sign-in endpoint of conf
  carrying a state, and their browser keeps the signed sign-in in a cookie
  (see the signin namespace)."
  [{:keys [conf db form-params] :as req}]
  (let [{:keys [me path]} form-params
        ;; A bare domain is welcome; canonical-url assumes https for it.
        me (indieauth/canonical-url me)]
    (if (and (:sign-in conf)
             me (indieauth/profile-url? me)
             (db/post-at-path db path))
      (let [{:keys [url cookie]} (signin/sign-in-request conf me path)]
        {:status  303
         :headers {"Location"   url
                   "Set-Cookie" (signin/cookie signin/sign-in-cookie cookie)}})
      (sign-in-failure conf))))

(defn sign-in-callback
  "Completes a visitor's Web sign-in: our state and the browser's cookie are
  verified, the code exchanged for their authenticated site, and they are
  sent back to the post.

  The fresh token in the redirect's query string puts the comment form right
  in the post's Responses section (see single-post)."
  [{:keys [conf db query-params] :as req}]
  (let [{:keys [me path]} (when (:sign-in conf)
                            (signin/sign-in! query-params
                                             (signin/cookie-value req signin/sign-in-cookie)))]
    (if (and me (db/post-at-path db path))
      {:status  303
       :headers {"Location"   (str path "?"
                                   (fetch.url/query-string
                                     {:signed-in (signin/comment-token me path)})
                                   "#comments")
                 "Set-Cookie" (signin/cookie signin/sign-in-cookie nil)}}
      (sign-in-failure conf))))

(defn post-comment
  "Accepts a signed-in visitor's comment. The signed comment token (see
  sign-in-callback) proves who they are and which post they came from; their
  homepage's h-card fills in the displayed name and photo. The watcher syncs
  the written file into the db."
  [{:keys [conf form-params] :as req}]
  (let [{:keys [token content]} form-params
        {:keys [me path]} (signin/read-comment-token token)
        content (some-> content str/trim not-empty)]
    (if (and me content
             (<= (count content) shared/comment-max-length))
      (do (comments/put-comment!
            (:indieweb-dir conf) path
            (shared/compact
              (merge {:status    :approved
                      :auth      :indieauth
                      :received  (str (Instant/now))
                      :published (str (LocalDate/now))
                      :content   content}
                     (webmention/author-attrs conf me))))
          {:status  303
           :headers {"Location" (str path "#comments")}})
      (sign-in-failure conf))))

(defn- sign-in-choice-page
  "Renders the provider chooser for begin!'s `choices`: each option links
  back to /auth with the original `query-params` plus its kind pinned as
  provider, so both the sign-in and the server role round-trip unchanged."
  [conf query-params choices]
  (html-response
    (c/page (str "Sign in — " (:name conf))
            (c/sign-in-chooser
              (or (:me query-params) (:url conf))
              (map (fn [{:keys [provider] :as option}]
                     (assoc option :choose-href
                            (str "/auth?" (fetch.url/query-string
                                            (assoc query-params
                                              :provider (name provider))))))
                   choices))
            conf
            :reader? true)))

(defn- authorize
  "The answer to a checked authorization `request` of our IndieAuth server
  under `conf`: the visitor or the owner is sent off to sign in, by way of
  the chooser when their homepage offers several providers. A visitor
  whose homepage offers none gets the styled \"what your homepage needs\"
  page."
  [conf {:keys [query-params] :as ring-request} request]
  (let [{:keys [redirect cookie choices]}
        (auth/begin! conf request :provider (:provider query-params))]
    (cond
      redirect                        {:status  303
                                       :headers (cond-> {"Location" redirect}
                                                  cookie (assoc "Set-Cookie" cookie))}
      choices                         (sign-in-choice-page conf query-params choices)
      (auth/own-request? conf request) (html-response 400 (c/page (str "Sign-in failed — " (:name conf))
                                                                  (c/sign-in-unsupported)
                                                                  conf
                                                                  :reader? true))
      :else                           (sign-in-failure conf))))

(defn auth
  "Our own IndieAuth authorization endpoint (see the auth namespace).

  A GET is a client's authorization request, which the server checks before
  `authorize` sends the person off to sign in. A POST redeems a code for the
  profile URL alone, e.g. our own sign-in client's."
  [{:keys [conf] :as req}]
  ((server/handler (assoc (auth/server-opts conf)
                          :authorize  (partial authorize conf)
                          :error-page (fn [_ _] (sign-in-failure conf))))
   req))

(defn- consent-page
  "The page that asks the site's owner under `conf` to consent to a client's
  request, from the `consent` of our IndieAuth server."
  [conf {:keys [client-id scopes token] :as consent}]
  (-> (html-response (c/page (str "Authorize app — " (:name conf))
                             (c/consent-form client-id
                                             (not-empty (str/join " " scopes))
                                             token)
                             conf
                             :reader? true))
      (update :headers merge server/page-headers)))

(defn- signed-in-response
  "The answer under `conf` once someone has signed in, as `result` has it
  (see auth/signed-in!): onward to the client with its code, the consent
  page for the owner, or the styled failure page."
  [conf {:keys [redirect consent] :as result}]
  (cond
    redirect {:status 303 :headers {"Location" redirect}}
    consent  (consent-page conf consent)
    :else    (sign-in-failure conf)))

(defn auth-consent
  "The owner's yes on the consent page: the client gets its code (see the
  auth namespace)."
  [{:keys [conf form-params] :as req}]
  (signed-in-response conf (auth/approve! conf (:token form-params))))

(defn- server-endpoint
  "A handler that answers with the indieauth-clj `handler-fn`, made with our
  server's options."
  [handler-fn]
  (fn [{:keys [conf] :as req}]
    ((handler-fn (auth/server-opts conf)) req)))

(def auth-token
  "Our IndieAuth token endpoint: a client redeems its code for a Micropub
  bearer token."
  (server-endpoint server/token-handler))

(def auth-revoke
  "Our IndieAuth revocation endpoint (RFC 7009)."
  (server-endpoint server/revocation-handler))

(def auth-metadata
  "Our IndieAuth server metadata (RFC 8414), which every page head links."
  (server-endpoint server/metadata-handler))

(defn auth-callback
  "Completes a sign-in begun at /auth when the :provider of the path sends
  the browser back (see the auth namespace), clearing the cookie that
  started it."
  [{:keys [conf path-params query-params] :as req}]
  (-> (signed-in-response conf (auth/callback! conf
                                               (keyword (:provider path-params))
                                               query-params
                                               (signin/cookie-value req auth/callback-cookie)))
      (assoc-in [:headers "Set-Cookie"] (signin/cookie auth/callback-cookie nil))))

(defn micropub
  "The Micropub endpoint: entry create/update/delete via POST, queries via
  GET. Auth is verified by micropub-clj against our own issued tokens (see
  the micropub namespace)."
  [{:keys [conf db] :as req}]
  ((micropub/endpoint db conf) req))

(defn media
  "The Micropub media endpoint: stores an uploaded file and returns its URL.
  Multipart parsing is a route-scoped interceptor (see service)."
  [{:keys [conf db] :as req}]
  ((micropub/media-endpoint db conf) req))

(defn sitemap
  [{:keys [conf db] :as req}]
  {:status  200
   :headers {"Content-Type" "application/xml"}
   :body    (feed/sitemap-xml conf (db/posts db)
                              :pages (db/pages db))})

(defn opensearch
  "Serves the OpenSearch description of the search page."
  [{:keys [conf] :as req}]
  {:status  200
   :headers {"Content-Type" "application/opensearchdescription+xml"}
   :body    (feed/opensearch-xml conf)})

(defn sitemap-xsl
  "Serves the sitemap stylesheet with the XSLT content type; the generic
  resource route falls back to octet-stream for .xsl, which browsers refuse
  to apply."
  [_]
  {:status  200
   :headers {"Content-Type" "application/xslt+xml"}
   :body    (slurp (io/resource "public/sitemap.xsl"))})

(defn bridgy-fed-redirect
  "Redirects /.well-known/webfinger and /.well-known/host-meta to Bridgy Fed,
  query string included (the ?resource=acct:… query is the whole request);
  this is what upgrades the fediverse handle from @domain@web.brid.gy to
  @domain@domain. NB: it must stay a redirect, since Bridgy Fed refuses a
  site that serves WebFinger itself."
  [{:keys [conf uri query-string] :as req}]
  {:status  302
   ;; :bridgy-fed ends in a slash and uri starts with one; doubling the slash
   ;; here would 404 on their end.
   :headers {"Location" (str (:bridgy-fed conf) (subs uri 1)
                             (when query-string (str "?" query-string)))}})

(defn api-catalog
  "The RFC 9727 catalog of the machine endpoints this host exposes, as an
  RFC 9264 linkset; each anchor is the endpoint and its service-doc the spec
  that documents how to speak to it."
  [{:keys [conf] :as req}]
  (let [{:keys [url webmention-endpoint micropub-endpoint media-endpoint]} conf
        entry (fn [anchor doc-href]
                {:anchor      anchor
                 :service-doc [{:href doc-href :type "text/html"}]})]
    {:status  200
     :headers {"Content-Type" "application/linkset+json"}
     :body    (json/write-value-as-string
                {:linkset [(entry webmention-endpoint "https://www.w3.org/TR/webmention/")
                           (entry micropub-endpoint "https://micropub.spec.indieweb.org/")
                           (entry media-endpoint "https://micropub.spec.indieweb.org/#media-endpoint")
                           (entry (str url shared/feed-path) "https://www.rssboard.org/rss-specification")]})}))

(defn security-txt
  "The RFC 9116 security contact. Generated from conf rather than served as a
  static file so the contact cannot go stale; Expires (a required field) rolls
  half a year ahead for the same reason."
  [{:keys [conf uri] :as req}]
  (let [expires (-> (Instant/now)
                    (.plus (Duration/ofDays 182))
                    (.truncatedTo ChronoUnit/SECONDS))]
    {:status  200
     :headers {"Content-Type" "text/plain;charset=utf-8"}
     :body    (str "Contact: mailto:" (:email conf) "\n"
                   "Expires: " expires "\n"
                   "Preferred-Languages: " (:language conf) "\n"
                   "Canonical: " (:url conf) uri "\n")}))
