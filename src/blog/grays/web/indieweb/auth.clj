(ns blog.grays.web.indieweb.auth
  "Our own IndieAuth endpoints at /auth, /auth/token and /auth/metadata,
  replacing both delegations that used to sit here: IndieLogin.com for
  signing visitors in, and indieauth.com for Micropub authentication
  (doc/plan-auth.md; doc/indieweb.md §8a).

  The endpoint plays two roles:

  - For *visitors*, Web sign-in: a visitor claims a URL (me) and we
    authenticate the claim, either by delegating to the IndieAuth server
    their page advertises (rel=indieauth-metadata, or the legacy
    rel=authorization_endpoint), acting as a spec-current client — PKCE, the
    RFC 9207 iss check, verification of the returned profile URL — or via
    RelMeAuth (https://indieweb.org/RelMeAuth): the page links a supported
    provider profile with rel=me, the provider authenticates them over
    OAuth, and the profile must link back, closing the loop. GitHub and
    Mastodon-style instances (via dynamic client registration, cached in
    oauth-clients.edn) are the providers, tried in the order the homepage
    lists them. The signin namespace is this role's client, unchanged since
    the IndieLogin days.
  - For *external clients* (a Micropub app like Quill), the spec server
    (https://indieauth.spec.indieweb.org/): an authorization request arrives
    with response_type=code, the site owner proves themselves through the
    very sign-in flow above, approves the request on the consent page, and
    the client redeems its code — at /auth for identity, at /auth/token for
    a bearer token to use against Micropub. Issued tokens are recorded
    (hashed) in tokens.edn, so verification is a local lookup and revocation
    is deleting a row (see the indieweb namespace).

  Continuity is the signin namespace's HMAC-signed tokens: provider state,
  authorization codes, and the consent round trip are all signed maps (told
  apart by :kind where confusion could hurt), so the endpoint keeps no
  session state; even the PKCE verifier we use as a client is re-derived as
  the state's own signature rather than stored. Codes are single-use per
  boot (see `redeemed`)."
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [taoensso.telemere :as tel]
            [blog.grays.web.http :as http]
            [blog.grays.web.shared :as shared]
            [blog.grays.web.indieweb :as indieweb]
            [blog.grays.web.indieweb.signin :as signin]
            [blog.grays.web.indieweb.webmention.html :as html])
  (:import [java.net URI]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest SecureRandom]
           [java.time Instant]
           [java.util Base64]))

(defn canonical-url
  "The canonical form of the URL `s` for identity comparison; nil when `s` is
  not an absolute http(s) URL.

  Lowercased scheme and host, default port and any fragment dropped, an empty
  path made \"/\" and a trailing slash trimmed elsewhere. A missing scheme is
  taken as https, the way people write \"example.com\" in a profile field."
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
  `conf`?

  The sign-in role accepts only ourselves as a client; external clients go
  through the spec's authorization request instead (see
  `authorization-request`)."
  [{:keys [url] :as conf} client-id redirect-uri]
  ;; The prefix includes the boundary slash, or a look-alike domain
  ;; (ours.evil.example) would pass as us.
  (boolean (and client-id redirect-uri
                (str/starts-with? (str client-id) (str url "/"))
                (str/starts-with? (str redirect-uri) (str url "/")))))

(defn our-client-id
  "The client id we present under the :url of `conf`: our home page, trailing
  slash included.

  The same convention as signin/client-id. The URL doubles as our issuer
  identifier in the server role, so approve! and interceptors/auth-metadata
  must both read it from here or RFC 9207 clients would see two issuers."
  [{:keys [url] :as conf}]
  (str url "/"))

(defn- fetch-page!
  "Fetch and parse the page at `url`, as {:response .. :doc ..}; nil when it
  cannot be read.

  The response rides along because rel discovery must read the Link headers
  too."
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

(defn- client-redirect
  "The client URL to send the authenticated visitor back to, carrying our
  authorization code for `me` and the client's own `state`."
  [me client-id redirect-uri state]
  (str redirect-uri "?"
       (http/form-encode {:code  (signin/token {:kind         :code
                                                :me           me
                                                :client-id    client-id
                                                :redirect-uri redirect-uri})
                          :state (str state)})))

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
       (http/form-encode {:client_id    (:client-id github)
                          :redirect_uri (github-callback-uri conf)
                          :state        state
                          :allow_signup "false"})))

(defn- github-user!
  "The GitHub user behind the OAuth `code`; nil when GitHub rejects it or
  cannot be reached.

  The code is exchanged for an access token, and the profile it belongs to is
  read with it."
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
  "Does the GitHub `user` profile point back at the canonical `me`?

  The blog field is the profile's \"website\"; this is the second half of
  RelMeAuth's bidirectional check (the first being the rel=me on the
  homepage)."
  [me user]
  (= me (canonical-url (:blog user))))

(defn github-callback!
  "Complete the GitHub half of a sign-in under `conf`: the client URL to
  redirect to; nil when any check fails, logged under ::rejected.

  Our `state` is verified, the OAuth `code` exchanged, and the profile
  checked both ways — it must be the username the homepage's rel=me named,
  and its website field must link back to the claimed me."
  [conf code state]
  (let [{:keys [me github client-id redirect-uri] :as m}
        (signin/read-token state signin/state-max-age)]
    (when (and me code)
      (let [user (github-user! conf code)]
        (if (and user
                 (= (str/lower-case github) (str/lower-case (str (:login user))))
                 (github-links-back? me user))
          (client-redirect me client-id redirect-uri (:state m))
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
  registering and caching it (oauth-clients.edn) on first contact.

  nil when the instance refuses or cannot be reached — including when the
  \"instance\" is just some website with an /@user URL."
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
         (http/form-encode {:response_type "code"
                            :client_id     client-id
                            :redirect_uri  (mastodon-callback-uri conf)
                            :scope         "read:accounts"
                            :state         state}))))

(defn- mastodon-account!
  "The account behind the OAuth `code` at the Mastodon `instance`; nil when
  the instance rejects it or cannot be reached.

  The code is exchanged for an access token, and the account it belongs to is
  read with it."
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
  closes RelMeAuth's loop. (A field the instance shows as verified is rel=me
  under the hood, but an unverified link back makes the same claim, and we
  already hold its other half.)"
  [me account]
  (boolean
    (->> (conj (map :value (:fields account)) (:note account))
         (remove str/blank?)
         (some (fn [s]
                 (->> (html/all-hrefs (html/parse s me))
                      (some #(= me (canonical-url %)))))))))

(defn mastodon-callback!
  "Complete the Mastodon half of a sign-in under `conf`: the client URL to
  redirect to; nil when any check fails, logged under ::rejected.

  Our `state` is verified, the OAuth `code` exchanged at the instance, and
  the account checked both ways — it must be the profile the homepage's
  rel=me named, and its bio or fields must link back to the claimed me."
  [conf code state]
  (let [{:keys [me mastodon instance client-id redirect-uri] :as m}
        (signin/read-token state signin/state-max-age)]
    (when (and me code instance)
      (let [account (mastodon-account! conf instance code)]
        (if (and account
                 (= (canonical-url mastodon) (canonical-url (:url account)))
                 (mastodon-links-back? me account))
          (client-redirect me client-id redirect-uri (:state m))
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

  The Link header wins over the HTML per the spec, and a relative header href
  resolves against the final URL."
  [{:keys [url] :as response} doc rel]
  (or (when-let [href (not-empty (str (http/header-rel response rel)))]
        (str (.resolve (URI. url) ^String href)))
      (html/rel-href doc rel)))

(defn- fetch-metadata!
  "The {:endpoint .. :issuer ..} of the IndieAuth server metadata document at
  `url`; nil when it cannot be read or fails the issuer check.

  The spec requires the issuer to be a prefix of the metadata URL, which is
  what stops a page pointing at somebody else's server."
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
  [conf me endpoint issuer client-id redirect-uri client-state]
  (let [state    (signin/token {:me           me
                                :endpoint     endpoint
                                :issuer       issuer
                                :client-id    client-id
                                :redirect-uri redirect-uri
                                :state        client-state})
        verifier (signin/signature state)]
    (str endpoint (if (str/includes? endpoint "?") "&" "?")
         (http/form-encode {:response_type         "code"
                            :client_id             (our-client-id conf)
                            :redirect_uri          (indieauth-callback-uri conf)
                            :state                 state
                            :code_challenge        (s256-challenge verifier)
                            :code_challenge_method "S256"
                            :me                    me}))))

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
  the spec against the claimed `me`.

  The me the visitor claimed passes as-is, while a different URL is accepted
  only when its own page advertises that same authorization endpoint, so a
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
  the client URL to redirect to; nil when any check fails, logged ::rejected.

  Our `state` is verified, `iss` must match the discovered issuer (RFC 9207;
  a legacy endpoint declares none, so none is checked), the `code` is
  redeemed at their endpoint under `conf` with the re-derived PKCE verifier,
  and the returned profile URL is verified per the spec."
  [conf code state iss]
  (let [{:keys [me endpoint issuer client-id redirect-uri] :as m}
        (signin/read-token state signin/state-max-age)]
    (when (and me code endpoint
               (or (nil? issuer) (= iss issuer)))
      (let [returned (redeem-remote-code! conf endpoint code
                                          (signin/signature state))]
        (if-let [me' (verified-me! me endpoint returned)]
          (client-redirect me' client-id redirect-uri (:state m))
          (do (tel/log! {:level :info
                         :id    ::rejected
                         :data  {:me me :endpoint endpoint :returned returned}
                         :msg   (str "Sign-in rejected for " me)})
              nil))))))

;;; The sign-in role (visitors; our own blog as the client)

(defn discovered-options
  "Every way the page fetched from a sign-in claim (its `response`/`doc`
  pair) says its owner can authenticate under `conf`, in order.

  Their own IndieAuth server comes first (never our own), then each supported
  rel=me provider as the page lists them. Plain descriptor maps — :kind
  :indieauth/:github/:mastodon plus what the chooser displays and what
  starting the flow needs; a Mastodon instance is not contacted until its
  option is chosen."
  [conf response doc]
  (distinct
    (concat
      (when-let [{:keys [endpoint issuer]} (auth-server response doc)]
        (when (and (http/valid-url? endpoint)
                   (not (str/starts-with? endpoint (str (:url conf) "/"))))
          [{:kind :indieauth :endpoint endpoint :issuer issuer}]))
      (keep (fn [href]
              (or (when-let [user (github-username [href])]
                    {:kind :github :user user})
                  (when-let [[_ instance user] (re-matches mastodon-profile-re href)]
                    {:kind :mastodon :instance instance :user user :href href})))
            (html/rel-hrefs doc "me")))))

(defn- option-authorize-url
  "The provider URL starting the flow of the discovered `option` for `me`
  under `conf`; nil when the flow cannot start.

  Our signed state carries `client-id`/`redirect-uri` and the client's own
  `client-state`; only a Mastodon flow can fail to start (the instance
  refusing to register us)."
  [conf me {:keys [kind] :as option} client-id redirect-uri client-state]
  (let [state (fn [extra]
                (signin/token (merge {:me           me
                                      :client-id    client-id
                                      :redirect-uri redirect-uri
                                      :state        client-state}
                                     extra)))]
    (case kind
      :indieauth (indieauth-authorize-url conf me (:endpoint option) (:issuer option)
                                          client-id redirect-uri client-state)
      :github    (github-authorize-url conf (state {:github (:user option)}))
      :mastodon  (mastodon-authorize-url conf (:instance option)
                                         (state {:mastodon (:href option)
                                                 :instance (:instance option)})))))

(defn begin!
  "Begin authenticating a visitor's claim to be `me` under `conf`:
  {:redirect url}, {:choices options}, or nil when the claim cannot be handled.

  The page at me is fetched and its options discovered (discovered-options).
  A single option — or the picked `provider`, a kind name from the chooser —
  yields the :redirect starting its flow; several options with none picked
  yield the :choices. Our signed state carries `client-id`/`redirect-uri` and
  the client's own `client-state` through whichever flow starts.

  The visitor's own IndieAuth server, when their page advertises one, is
  offered first — except our own: we cannot delegate to ourselves, so the
  site owner's claim (whose page advertises this very endpoint) offers the
  rel=me providers alone."
  [conf me client-id redirect-uri client-state & {:keys [provider]}]
  (when-let [me (canonical-url me)]
    (when (http/valid-url? me)
      (when-let [{:keys [response doc]} (fetch-page! me)]
        (let [options (cond->> (discovered-options conf response doc)
                        provider (filter #(= provider (name (:kind %)))))]
          (if (and (next options) (not provider))
            {:choices options}
            (some (fn [option]
                    (some->> (option-authorize-url conf me option
                                                   client-id redirect-uri client-state)
                             (hash-map :redirect)))
                  options)))))))

(def code-max-age
  "How long an authorization code stays valid, in ms; the client redeems it
  immediately after the redirect."
  (* 60 1000))

(defonce redeemed
  ;; Codes already redeemed this boot: a code is single-use, and the codes
  ;; are stateless signed tokens, so use has to be tracked here.
  (atom #{}))

(defn redeem!
  "Redeem the authorization `code` presented by the client at
  `client-id`/`redirect-uri`: its claims (:me and :scope), or nil.

  The code must be ours, young, unused, issued to that same client, and —
  when it carries a PKCE challenge — accompanied by the matching `verifier`.
  Only a code for an approved external request carries :scope."
  [code client-id redirect-uri verifier]
  (when-let [m (signin/read-token code code-max-age)]
    (let [[prior _] (swap-vals! redeemed conj code)]
      (when (and (= :code (:kind m))
                 (not (prior code))
                 (= client-id (:client-id m))
                 (= redirect-uri (:redirect-uri m))
                 (or (nil? (:challenge m))
                     (and verifier (= (:challenge m) (s256-challenge verifier)))))
        m))))

;;; The server role (external clients, e.g. a Micropub app)

(defn- consent-uri
  [{:keys [url] :as conf}]
  (str url "/auth/consent"))

(defn- same-origin?
  [a b]
  (try
    (let [ua (URI. (str a))
          ub (URI. (str b))]
      (and (= (.getScheme ua) (.getScheme ub))
           (= (.getHost ua) (.getHost ub))
           (= (.getPort ua) (.getPort ub))))
    (catch Exception _ false)))

(defn- registered-redirect?
  "Does the `client-id` page list `redirect-uri` under rel=redirect_uri? The
  spec's escape hatch for a client whose callback lives off its own origin."
  [client-id redirect-uri]
  (boolean
    (when-let [{:keys [doc]} (fetch-page! client-id)]
      (some #{redirect-uri} (html/rel-hrefs doc "redirect_uri")))))

(defn authorization-request
  "Validate an external client's spec authorization request `params` under
  `conf`: the signed request token the consent flow rides on; nil otherwise.

  Required: response_type=code, a public client_id whose origin (or published
  rel=redirect_uri list) covers redirect_uri, and an S256 PKCE challenge."
  [conf {:keys [response_type client_id redirect_uri state
                code_challenge code_challenge_method scope] :as params}]
  (when (and (= "code" response_type)
             (http/valid-url? client_id)
             redirect_uri
             (or (same-origin? client_id redirect_uri)
                 (registered-redirect? client_id redirect_uri))
             (not-empty code_challenge)
             (= "S256" code_challenge_method))
    (signin/token {:kind         :authz-request
                   :client-id    client_id
                   :redirect-uri redirect_uri
                   :client-state state
                   :challenge    code_challenge
                   :scope        (not-empty (str scope))})))

(defn authorize-request!
  "Handle an external client's authorization request `params` under `conf`
  (the server role of /auth): begin!'s {:redirect ..} or {:choices ..}.

  The validated request sends the visitor into the sign-in flow to prove they
  are the site owner, the whole request riding along as the signed
  client-state and /auth/consent as the return address; the owner too may get
  the provider chooser, with :provider in params pinning a choice. nil when
  the request is invalid (or the owner's own homepage cannot be read)."
  [conf params]
  (when-let [request-token (authorization-request conf params)]
    (begin! conf (:url conf) (our-client-id conf) (consent-uri conf)
            request-token
            :provider (:provider params))))

(defn consent-request
  "Complete the owner's sign-in leg of an external authorization request:
  {:client-id .. :scope .. :token ..} for the consent page; nil otherwise.

  The GET half of /auth/consent: the sign-in `code` is redeemed against the
  `request-token` under `conf`, the authenticated me must be the site itself,
  and everything the approval POST must carry is re-signed as :token."
  [conf code request-token]
  (when-let [{:keys [kind] :as request}
             (signin/read-token request-token signin/state-max-age)]
    (when (= :authz-request kind)
      (let [me (:me (redeem! code (our-client-id conf) (consent-uri conf) nil))]
        (when (= me (canonical-url (:url conf)))
          {:client-id (:client-id request)
           :scope     (:scope request)
           :token     (signin/token (assoc request :kind :authz-consent :me me))})))))

(defn approve!
  "Approve an external authorization request under `conf`: the client URL to
  redirect to; nil otherwise.

  The POST half of /auth/consent: the signed `consent-token` minted by
  consent-request proves the owner said yes, and the URL carries the
  authorization code, the client's own state, and our iss (RFC 9207)."
  [conf consent-token]
  (when-let [{:keys [kind me client-id redirect-uri client-state challenge scope]}
             (signin/read-token consent-token signin/state-max-age)]
    (when (= :authz-consent kind)
      (str redirect-uri (if (str/includes? redirect-uri "?") "&" "?")
           (http/form-encode
             (shared/compact {:code  (signin/token {:kind         :code
                                                    :me           me
                                                    :client-id    client-id
                                                    :redirect-uri redirect-uri
                                                    :challenge    challenge
                                                    :scope        scope})
                              :state client-state
                              ;; The issuer is the site itself, the same
                              ;; home-page URL we use as client-id elsewhere.
                              :iss   (our-client-id conf)}))))))

(defn- new-token
  []
  (let [bs (byte-array 32)]
    (.nextBytes (SecureRandom.) bs)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bs)))

(defn issue-token!
  "Issue a fresh bearer token for the redeemed code claims `m` under `conf`:
  the spec token response map.

  The token is recorded (hashed) in the tokens.edn of :indieweb-dir;
  verification is indieweb/find-token, and revocation is deleting the row."
  [{:keys [indieweb-dir] :as conf} {:keys [me client-id scope] :as m}]
  (let [token (new-token)]
    (indieweb/put-token! indieweb-dir token
                         {:me        me
                          :client-id client-id
                          :scope     scope
                          :issued    (str (Instant/now))})
    {:access_token token
     :token_type   "Bearer"
     :me           me
     :scope        scope}))

(comment
  (require '[blog.grays.web.service :as service])

  (canonical-url "EXAMPLE.com/Path/")                       ; scheme added, host down-cased
  (canonical-url "https://example.com:443/#top")            ; default port and fragment dropped
  (canonical-url "ftp://example.com/")                      ; => nil

  ;; Discovery, live: a homepage advertising its own IndieAuth server wins
  ;; over rel=me; our own homepage advertises *us*, so the owner falls
  ;; through to GitHub instead of looping.
  (begin! service/conf "aaronparecki.com" "https://simon.grays.blog/"
          "https://simon.grays.blog/sign-in/callback" "s")
  (begin! service/conf "simon.grays.blog" "https://simon.grays.blog/"
          "https://simon.grays.blog/sign-in/callback" "s")

  ;; The full server-role round trip minus the providers: request -> owner
  ;; sign-in code -> consent -> approval -> code redemption with PKCE.
  (let [conf     service/conf
        verifier "test-verifier-test-verifier-test-verifier-43"
        request  (authorization-request
                   conf {:response_type         "code"
                         :client_id             "https://quill.p3k.io/"
                         :redirect_uri          "https://quill.p3k.io/redirect"
                         :state                 "quill-state"
                         :code_challenge        (s256-challenge verifier)
                         :code_challenge_method "S256"
                         :scope                 "create"})
        sign-in  (signin/token {:kind         :code
                                :me           "https://simon.grays.blog/"
                                :client-id    "https://simon.grays.blog/"
                                :redirect-uri "https://simon.grays.blog/auth/consent"})
        consent  (consent-request conf sign-in request)
        approved (approve! conf (:token consent))
        code     (second (re-find #"code=([^&]+)" approved))]
    (redeem! (java.net.URLDecoder/decode code) "https://quill.p3k.io/"
             "https://quill.p3k.io/redirect" verifier))
  #_.)
