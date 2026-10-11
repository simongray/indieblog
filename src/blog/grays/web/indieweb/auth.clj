(ns blog.grays.web.indieweb.auth
  "Our own IndieAuth server at /auth, /auth/token, /auth/revoke and
  /auth/metadata, on indieauth-clj's server (doc/plan-auth.md;
  doc/indieweb.md §8a), and the ways a person signs in to it.

  The server checks every client's authorization request, issues the codes
  and tokens, and keeps them. What it leaves to us is signing the person in.
  Two clients ask. Our own blog asks for a *visitor*, for Web sign-in: a
  claimed URL (me) is authenticated either by delegating to the IndieAuth
  server their page advertises, or via RelMeAuth
  (https://indieweb.org/RelMeAuth) through a rel=me GitHub or Mastodon-style
  profile that links back, and the code follows at once. Any other client,
  e.g. a Micropub app like Quill, asks for the *site owner*, who signs in
  the same way and approves on the consent page.

  The checked request rides through the providers in the server's signed
  request token, inside our own signed provider state (see the signin
  namespace). Codes last a minute in memory; tokens are kept hashed in
  tokens.edn."
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [taoensso.telemere :as tel]
            [dk.simongray.indieauth :as indieauth]
            [dk.simongray.indieauth.server :as server]
            [dk.simongray.wary-fetch.url :as fetch.url]
            [blog.grays.web.http :as http]
            [blog.grays.web.shared :as shared]
            [blog.grays.web.indieweb :as indieweb]
            [blog.grays.web.indieweb.signin :as signin]
            [blog.grays.web.indieweb.webmention.html :as html])
  (:import [java.net URI]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.time Instant]
           [java.util Base64]))

(defn canonical-url
  "The canonical form of the URL `s` for identity comparison; nil when `s` is
  not an absolute http(s) URL.

  Lowercased scheme and host, default port and any fragment dropped, an empty
  path made \"/\", a trailing slash trimmed on any other path. A missing
  scheme is taken as https, the way people write \"example.com\" in a profile
  field."
  [s]
  (let [s (str s)
        s (if (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*:" s) s (str "https://" s))]
    (when-let [uri (try (URI. s) (catch Exception _ nil))]
      (let [scheme (some-> (.getScheme uri) str/lower-case)
            host   (some-> (.getHost uri) str/lower-case)
            port   (.getPort uri)
            path   (or (not-empty (.getPath uri)) "/")]
        (when (and host (#{"http" "https"} scheme))
          (str scheme "://" host
               (when-not (or (= port -1)
                             (and (= scheme "https") (= port 443))
                             (and (= scheme "http") (= port 80)))
                 (str ":" port))
               (if (= path "/") "/" (str/replace path #"/$" ""))
               (some->> (.getQuery uri) (str "?"))))))))

(defn own-client?
  "Is the `client-id`/`redirect-uri` pair our own blog under the :url of
  `conf`? The sign-in role accepts only ourselves as a client."
  [{:keys [url] :as conf} client-id redirect-uri]
  ;; The prefix includes the boundary slash, or a look-alike domain
  ;; (ours.evil.example) would pass as us.
  (boolean (and client-id redirect-uri
                (str/starts-with? (str client-id) (str url "/"))
                (str/starts-with? (str redirect-uri) (str url "/")))))

(defn our-client-id
  "The client id we present under the :url of `conf`: our home page, trailing
  slash included.

  Doubles as our issuer identifier (RFC 9207) in server-opts, and as the
  owner's profile URL; signin/client-id mints the same string on its side of
  the require boundary."
  [{:keys [url] :as conf}]
  (str url "/"))

(defn- fetch-page!
  "Fetch and parse the page at `url`, as {:response .. :doc ..}; nil when it
  cannot be read. The response rides along so rel discovery can read the
  Link headers too."
  [url]
  (try
    (let [{:keys [body] :as response} (http/get! url)]
      (when (http/ok? response)
        {:response response
         :doc      (html/parse body (:url response))}))
    (catch Exception e
      (tel/log! {:level :info
                 :id    ::fetch-error
                 :data  {:url url}
                 :msg   (str "Could not fetch " url ": " (ex-message e))})
      nil)))

(declare server-opts signed-in!)

;;; GitHub (RelMeAuth)

(def github-profile-re
  ;; A GitHub profile URL; the sole path segment is the username.
  #"https?://(?:www\.)?github\.com/([A-Za-z0-9-]+)/?")

(defn github-username
  "The username in the first of the rel=me `hrefs` that is a GitHub profile
  URL; nil when none is."
  [hrefs]
  (some #(second (re-matches github-profile-re %)) hrefs))

(defn- github-callback-uri
  [{:keys [url] :as conf}]
  (str url "/auth/callback/github"))

(defn- github-authorize-url
  "The GitHub OAuth URL to send the visitor to, carrying our signed `state`;
  app credentials come from the :github of `conf` (see :secrets-file)."
  [{:keys [github] :as conf} state]
  (str "https://github.com/login/oauth/authorize?"
       (fetch.url/query-string {:client_id    (:client-id github)
                                :redirect_uri (github-callback-uri conf)
                                :state        state
                                :allow_signup "false"})))

(defn- github-user!
  "The GitHub user behind the OAuth `code`; nil when GitHub rejects it or
  cannot be reached."
  [{:keys [github] :as conf} code]
  (try
    (let [{:keys [status body]} (http/post-form!
                                  "https://github.com/login/oauth/access_token"
                                  {:client_id     (:client-id github)
                                   :client_secret (:client-secret github)
                                   :code          code
                                   :redirect_uri  (github-callback-uri conf)}
                                  {"Accept" "application/json"})
          token (when (= 200 status)
                  (:access_token (json/read-value body json/keyword-keys-object-mapper)))]
      (when token
        (let [{:keys [status body]} (http/get! "https://api.github.com/user"
                                               {"Authorization" (str "Bearer " token)
                                                "Accept"        "application/vnd.github+json"})]
          (when (= 200 status)
            (json/read-value body json/keyword-keys-object-mapper)))))
    (catch Exception e
      (tel/log! {:level :warn
                 :id    ::github-error
                 :msg   (str "Could not reach GitHub: " (ex-message e))})
      nil)))

(defn github-links-back?
  "Does the GitHub `user` profile point back at the canonical `me`? The
  :blog field is the profile's \"website\"; the second half of RelMeAuth's
  bidirectional check."
  [me user]
  (= me (canonical-url (:blog user))))

(defn github-callback!
  "Complete the GitHub half of a sign-in under `conf`: our `state` is
  verified, the OAuth `code` exchanged, and the profile checked both ways
  against the claimed me. Returns what signed-in! gives; nil when any check
  fails, logged under ::rejected."
  [conf code state]
  (let [{:keys [me github request] :as m}
        (signin/read-token state signin/state-max-age)]
    (when (and me code)
      (let [user (github-user! conf code)]
        (if (and user
                 (= (str/lower-case github) (str/lower-case (str (:login user))))
                 (github-links-back? me user))
          (signed-in! conf me request)
          (do (tel/log! {:level :info
                         :id    ::rejected
                         :data  {:me me :github github :login (:login user)}
                         :msg   (str "Sign-in rejected for " me)})
              nil))))))

;;; Mastodon (RelMeAuth, via per-instance dynamic client registration)

(def mastodon-profile-re
  ;; A Mastodon-style profile URL: the instance origin, then /@username.
  #"(https?://[^/]+)/@([A-Za-z0-9_]+)/?")

(defn- mastodon-callback-uri
  [{:keys [url] :as conf}]
  (str url "/auth/callback/mastodon"))

(defn- mastodon-client!
  "The OAuth client we are registered as at the Mastodon `instance`,
  registering and caching it (oauth-clients.edn) on first contact; nil when
  the instance refuses or cannot be reached."
  [{:keys [indieweb-dir] :as conf} instance]
  (or (indieweb/find-oauth-client indieweb-dir instance)
      (try
        (let [{:keys [status body]} (http/post-form!
                                      (str instance "/api/v1/apps")
                                      {:client_name   (:name conf)
                                       :redirect_uris (mastodon-callback-uri conf)
                                       :scopes        "read:accounts"
                                       :website       (str (:url conf) "/")})]
          (when (= 200 status)
            (let [{:keys [client_id client_secret]}
                  (json/read-value body json/keyword-keys-object-mapper)]
              (when (and client_id client_secret)
                (let [registration {:client-id     client_id
                                    :client-secret client_secret
                                    :registered    (str (Instant/now))}]
                  (indieweb/put-oauth-client! indieweb-dir instance registration)
                  registration)))))
        (catch Exception e
          (tel/log! {:level :info
                     :id    ::mastodon-error
                     :data  {:instance instance}
                     :msg   (str "Could not register with " instance ": "
                                 (ex-message e))})
          nil))))

(defn- mastodon-authorize-url
  "The OAuth URL at the Mastodon `instance` to send the visitor to, carrying
  our signed `state`; nil when we cannot register as a client there."
  [conf instance state]
  (when-let [{:keys [client-id]} (and (http/valid-url? instance)
                                      (mastodon-client! conf instance))]
    (str instance "/oauth/authorize?"
         (fetch.url/query-string {:response_type "code"
                                  :client_id     client-id
                                  :redirect_uri  (mastodon-callback-uri conf)
                                  :scope         "read:accounts"
                                  :state         state}))))

(defn- mastodon-account!
  "The account behind the OAuth `code` at the Mastodon `instance`; nil when
  the instance rejects it or cannot be reached."
  [conf instance code]
  (try
    (when-let [{:keys [client-id client-secret]} (mastodon-client! conf instance)]
      (let [{:keys [status body]} (http/post-form!
                                    (str instance "/oauth/token")
                                    {:grant_type    "authorization_code"
                                     :code          code
                                     :client_id     client-id
                                     :client_secret client-secret
                                     :redirect_uri  (mastodon-callback-uri conf)
                                     :scope         "read:accounts"}
                                    {"Accept" "application/json"})
            token (when (= 200 status)
                    (:access_token (json/read-value body json/keyword-keys-object-mapper)))]
        (when token
          (let [{:keys [status body]} (http/get! (str instance
                                                      "/api/v1/accounts/verify_credentials")
                                                 {"Authorization" (str "Bearer " token)})]
            (when (= 200 status)
              (json/read-value body json/keyword-keys-object-mapper))))))
    (catch Exception e
      (tel/log! {:level :warn
                 :id    ::mastodon-error
                 :data  {:instance instance}
                 :msg   (str "Could not reach " instance ": " (ex-message e))})
      nil)))

(defn mastodon-links-back?
  "Does the Mastodon `account` profile point back at the canonical `me`?
  The bio (:note) and profile :fields are HTML; a link back in any of them
  closes RelMeAuth's loop."
  [me account]
  (boolean
    (->> (conj (map :value (:fields account)) (:note account))
         (remove str/blank?)
         (some (fn [s]
                 (->> (html/all-hrefs (html/parse s me))
                      (some #(= me (canonical-url %)))))))))

(defn mastodon-callback!
  "Complete the Mastodon half of a sign-in under `conf`: our `state` is
  verified, the OAuth `code` exchanged at the instance, and the account
  checked both ways against the claimed me. Returns what signed-in! gives;
  nil when any check fails, logged under ::rejected."
  [conf code state]
  (let [{:keys [me mastodon instance request] :as m}
        (signin/read-token state signin/state-max-age)]
    (when (and me code instance)
      (let [account (mastodon-account! conf instance code)]
        (if (and account
                 (= (canonical-url mastodon) (canonical-url (:url account)))
                 (mastodon-links-back? me account))
          (signed-in! conf me request)
          (do (tel/log! {:level :info
                         :id    ::rejected
                         :data  {:me me :mastodon mastodon :account (:url account)}
                         :msg   (str "Sign-in rejected for " me)})
              nil))))))

;;; IndieAuth delegation (the visitor runs their own authorization server)

(defn- indieauth-callback-uri
  [{:keys [url] :as conf}]
  (str url "/auth/callback/indieauth"))

(defn- rel-url
  "The URL of the first `rel` link advertised by the `response`/`doc` pair.
  The Link header wins over the HTML per the spec, and a relative header
  href resolves against the final URL."
  [{:keys [url] :as response} doc rel]
  (or (some->> (http/header-rel response rel)
               (not-empty)
               (fetch.url/absolute-url url))
      (html/rel-href doc rel)))

(defn- fetch-metadata!
  "The {:endpoint .. :issuer ..} of the IndieAuth server metadata document at
  `url`; nil when it cannot be read, or when the issuer is not a prefix of
  the metadata URL as the spec requires."
  [url]
  (when (http/valid-url? url)
    (try
      (let [{:keys [status body]} (http/get! url {"Accept" "application/json"})
            {:keys [issuer authorization_endpoint]}
            (when (= 200 status)
              (json/read-value body json/keyword-keys-object-mapper))]
        (when (and authorization_endpoint issuer
                   (str/starts-with? url issuer))
          {:endpoint authorization_endpoint
           :issuer   issuer}))
      (catch Exception e
        (tel/log! {:level :info
                   :id    ::metadata-error
                   :data  {:url url}
                   :msg   (str "Could not read IndieAuth metadata " url ": "
                               (ex-message e))})
        nil))))

(defn- auth-server
  "The IndieAuth server advertised by the page in `response`/`doc`, as
  {:endpoint .. :issuer ..}; nil when none is advertised.

  rel=indieauth-metadata supplies both keys; the legacy
  rel=authorization_endpoint has no metadata, and so no issuer to check."
  [response doc]
  (if-let [metadata-url (rel-url response doc "indieauth-metadata")]
    (fetch-metadata! metadata-url)
    (when-let [endpoint (rel-url response doc "authorization_endpoint")]
      {:endpoint endpoint})))

(defn s256-challenge
  "The PKCE S256 code challenge of `verifier` (RFC 7636)."
  [^String verifier]
  (->> (.digest (MessageDigest/getInstance "SHA-256")
                (.getBytes verifier StandardCharsets/UTF_8))
       (.encodeToString (.withoutPadding (Base64/getUrlEncoder)))))

(defn- indieauth-authorize-url
  "The URL at the visitor's authorization `endpoint` to send them to,
  authenticating their claim to be `me`.

  Our signed state carries everything the callback must know, and the PKCE
  verifier is that state's own signature, so nothing is stored."
  [conf me endpoint issuer request]
  (let [state    (signin/token {:me       me
                                :endpoint endpoint
                                :issuer   issuer
                                :request  request})
        verifier (signin/signature state)]
    (fetch.url/with-query endpoint
                          {:response_type         "code"
                           :client_id             (our-client-id conf)
                           :redirect_uri          (indieauth-callback-uri conf)
                           :state                 state
                           :code_challenge        (s256-challenge verifier)
                           :code_challenge_method "S256"
                           :me                    me})))

(defn- redeem-remote-code!
  "The profile URL returned by redeeming `code` at the visitor's
  authorization `endpoint` with the PKCE `verifier`, canonicalized; nil when
  the endpoint rejects it or cannot be reached."
  [conf endpoint code verifier]
  (try
    (let [{:keys [status body]} (http/post-form!
                                  endpoint
                                  {:grant_type    "authorization_code"
                                   :code          code
                                   :client_id     (our-client-id conf)
                                   :redirect_uri  (indieauth-callback-uri conf)
                                   :code_verifier verifier}
                                  {"Accept" "application/json"})]
      (when (= 200 status)
        (some-> (json/read-value body json/keyword-keys-object-mapper)
                (:me)
                (canonical-url))))
    (catch Exception e
      (tel/log! {:level :warn
                 :id    ::exchange-error
                 :data  {:endpoint endpoint}
                 :msg   (str "Could not redeem code at " endpoint ": "
                             (ex-message e))})
      nil)))

(defn- verified-me!
  "The `returned` profile URL of a code exchange at `endpoint`, verified per
  the spec against the claimed `me`: it passes as-is when equal, and a
  different URL only when its own page advertises that same endpoint, so a
  server can only vouch for its own users."
  [me endpoint returned]
  (when (and returned (http/valid-url? returned))
    (if (= me returned)
      returned
      (when-let [{:keys [response doc]} (fetch-page! returned)]
        (when (= endpoint (:endpoint (auth-server response doc)))
          returned)))))

(defn indieauth-callback!
  "Complete the IndieAuth half of a sign-in against the visitor's own server:
  our `state` is verified, `iss` checked against the discovered issuer
  (RFC 9207), the `code` redeemed under `conf` with the re-derived PKCE
  verifier, and the returned profile URL verified (see verified-me!).
  Returns what signed-in! gives; nil when any check fails, logged
  ::rejected."
  [conf code state iss]
  (let [{:keys [me endpoint issuer request] :as m}
        (signin/read-token state signin/state-max-age)]
    (when (and me code endpoint
               (or (nil? issuer) (= iss issuer)))
      (let [verifier (signin/signature state)
            returned (redeem-remote-code! conf endpoint code verifier)]
        (if-let [me' (verified-me! me endpoint returned)]
          (signed-in! conf me' request)
          (do (tel/log! {:level :info
                         :id    ::rejected
                         :data  {:me me :endpoint endpoint :returned returned}
                         :msg   (str "Sign-in rejected for " me)})
              nil))))))

;;; Signing in, for a checked authorization request

(defn discovered-options
  "Every way the page fetched from a sign-in claim (its `response`/`doc`
  pair) says its owner can authenticate under `conf`, in order: their own
  IndieAuth server first (never our own), then each supported rel=me
  provider as the page lists them. Plain descriptor maps, keyed by :kind; a
  Mastodon instance is not contacted until its option is chosen, and GitHub
  is offered only when `conf` has the :github app credentials."
  [conf response doc]
  (distinct
    (concat
      (when-let [{:keys [endpoint issuer]} (auth-server response doc)]
        (when (and (http/valid-url? endpoint)
                   (not (str/starts-with? endpoint (str (:url conf) "/"))))
          [{:kind :indieauth :endpoint endpoint :issuer issuer}]))
      (keep (fn [href]
              (or (when-let [user (and (:github conf) (github-username [href]))]
                    {:kind :github :user user})
                  (when-let [[_ instance user] (re-matches mastodon-profile-re href)]
                    {:kind :mastodon :instance instance :user user :href href})))
            (html/rel-hrefs doc "me")))))

(defn- option-authorize-url
  "The provider URL starting the flow of the discovered `option` for `me`
  under `conf`, carrying the server's signed `request`; nil when the flow
  cannot start (only a Mastodon instance refusing to register us)."
  [conf me {:keys [kind] :as option} request]
  (let [state (fn [extra]
                (signin/token (merge {:me me :request request} extra)))]
    (case kind
      :indieauth (indieauth-authorize-url conf me (:endpoint option) (:issuer option)
                                          request)
      :github    (github-authorize-url conf (state {:github (:user option)}))
      :mastodon  (mastodon-authorize-url conf (:instance option)
                                         (state {:mastodon (:href option)
                                                 :instance (:instance option)})))))

(defn own-request?
  "Is the checked authorization `request` our own blog's under `conf`, i.e.
  a visitor's Web sign-in?"
  [conf {:keys [client-id redirect-uri] :as request}]
  (own-client? conf client-id redirect-uri))

(defn begin!
  "Begin signing in whoever the checked authorization `request` of our
  server needs under `conf`: {:redirect url}, {:choices options}, or nil
  when they cannot sign in.

  Our own blog asks for the visitor its request names as me, and any other
  client for the site's owner. Their page is fetched and its options
  discovered (discovered-options). A single option — or the picked
  `provider`, a kind name from the chooser — yields the :redirect starting
  its flow; several options with none picked yield the :choices. The
  request, signed, rides through whichever flow starts."
  [conf request & {:keys [provider]}]
  (when-let [me (canonical-url (if (own-request? conf request)
                                 (:me request)
                                 (our-client-id conf)))]
    (when (http/valid-url? me)
      (when-let [{:keys [response doc]} (fetch-page! me)]
        (let [token   (server/request-token request (server-opts conf))
              options (cond->> (discovered-options conf response doc)
                        provider (filter #(= provider (name (:kind %)))))]
          (if (and (next options) (not provider))
            {:choices options}
            (some (fn [option]
                    (when-let [redirect (option-authorize-url conf me option token)]
                      {:redirect redirect}))
                  options)))))))

;;; The server (indieauth-clj)

(defonce secret
  ;; Signs the server's requests and consents; random per boot, like the
  ;; signin namespace's, so a restart merely voids the sign-ins under way.
  (indieauth/token))

(defonce codes
  ;; A code lasts a minute, so memory is enough.
  (server/memory-store))

(defn server-opts
  "The options of indieauth-clj's server under `conf`: our endpoints, the
  stores of codes and tokens, and how long tokens last."
  [{:keys [indieauth indieweb-dir] :as conf}]
  {:issuer                 (our-client-id conf)
   :metadata-url           (:metadata indieauth)
   :authorization-endpoint (:authorization-endpoint indieauth)
   :token-endpoint         (:token-endpoint indieauth)
   :revocation-endpoint    (:revocation-endpoint indieauth)
   ;; What Micropub acts on, besides the profile; see micropub/authorize.
   :scopes-supported       ["profile" "email" "create" "update" "delete" "media"]
   ;; Micropub clients seldom refresh a token, so one lasts until it's
   ;; revoked, as ours always have.
   :token-seconds          nil
   :refresh-seconds        nil
   :secret                 secret
   :codes                  codes
   :tokens                 (indieweb/token-store indieweb-dir)})

(defn- owner-profile
  "The profile of the site's owner under `conf`, which a client gets with
  the profile scope."
  [{:keys [author email photo url] :as conf}]
  (shared/compact {:name  author
                   :url   (our-client-id conf)
                   :photo (some->> photo (str url))
                   :email email}))

(defn signed-in!
  "Carry on with the server's signed `request` now that `me` has signed in
  under `conf`: our own blog's request gets its code at once, as
  {:redirect ..}; another client's needs the site's owner, who is shown the
  consent page of {:consent ..}. Nil when `me` may not answer the request."
  [conf me request]
  (let [opts (server-opts conf)
        me   (indieauth/canonical-url me)]
    (when-let [request (and me
                            (indieauth/profile-url? me)
                            (server/token-request request opts))]
      (cond
        (own-request? conf request)
        (server/approve! (:token (server/consent request me opts)) {:me me} opts)

        (= me (our-client-id conf))
        {:consent (server/consent request me opts)}))))

(defn approve!
  "Give the client its code now that the owner has said yes on the consent
  page, whose signed `token` they posted under `conf`: {:redirect ..}, or
  {:error ..}."
  [conf token]
  (server/approve! token
                   {:me      (our-client-id conf)
                    :profile (owner-profile conf)}
                   (server-opts conf)))

(comment
  (require '[blog.grays.web.service :as service])

  (canonical-url "EXAMPLE.com/Path/")                       ; scheme added, host down-cased
  (canonical-url "https://example.com:443/#top")            ; default port and fragment dropped
  (canonical-url "ftp://example.com/")                      ; => nil

  ;; Discovery, live: a homepage advertising its own IndieAuth server wins
  ;; over rel=me; our own homepage advertises *us*, so the owner falls
  ;; through to GitHub instead of looping.
  (begin! service/conf {:client-id    "https://simon.grays.blog/"
                        :redirect-uri "https://simon.grays.blog/sign-in/callback"
                        :me           "https://aaronparecki.com/"})
  (begin! service/conf {:client-id    "https://quill.p3k.io/"
                        :redirect-uri "https://quill.p3k.io/redirect"})

  ;; Once the owner has signed in, a client's request shows the consent page.
  (let [conf    service/conf
        request {:client-id      "https://quill.p3k.io/"
                 :redirect-uri   "https://quill.p3k.io/redirect"
                 :state          "quill-state"
                 :code-challenge (indieauth/code-challenge (indieauth/code-verifier))
                 :scopes         ["create"]}]
    (signed-in! conf "https://simon.grays.blog/"
                (server/request-token request (server-opts conf))))
  #_.)
