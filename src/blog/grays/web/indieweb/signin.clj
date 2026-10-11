(ns blog.grays.web.indieweb.signin
  "Web sign-in for visitors (https://indieweb.org/Web_sign-in): a visitor
  claims their website URL, the :sign-in :endpoint of conf (in practice our
  own /auth, served by the auth namespace) authenticates the claim, and we
  learn a verified site to attribute their comment to.

  There are no sessions. Continuity is carried by short-lived signed tokens,
  and by one cookie: the nonce that ties a sign-in's callback to the browser
  that started it, so that nobody can finish their own sign-in in another's
  browser (RFC 9700, section 4.7). The secrets are random per boot;
  restarting merely voids in-flight sign-ins."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [taoensso.telemere :as tel]
            [dk.simongray.indieauth :as indieauth]
            [dk.simongray.wary-fetch.url :as fetch.url]
            [blog.grays.web.http :as http])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest SecureRandom]
           [java.util Base64]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

(def state-max-age
  "How long a visitor gets to complete authentication at the endpoint, in ms."
  (* 10 60 1000))

(def comment-max-age
  "How long a signed-in visitor gets to write their comment, in ms."
  (* 30 60 1000))

(defonce secret
  (let [bs (byte-array 32)]
    (.nextBytes (SecureRandom.) bs)
    bs))

(defn- hmac
  "The HMAC-SHA256 of the string `s` under the per-boot secret."
  ^bytes [^String s]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. secret "HmacSHA256"))
    (.doFinal mac (.getBytes s StandardCharsets/UTF_8))))

(defn- b64
  [^bytes bs]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bs))

(defn- unb64
  ^bytes [^String s]
  (.decode (Base64/getUrlDecoder) s))

(defn signature
  "The URL-safe signature of the string `s` under the per-boot secret.

  For when a secret must be *re-derivable* rather than round-tripped: the
  auth namespace derives its PKCE verifier from its state token this way, so
  the verifier is never sent anywhere yet needs no storing."
  [s]
  (b64 (hmac s)))

(defn token
  "Sign the map `m` into a URL-safe token, stamped with the current time; only
  `read-token` under the same boot's secret can open it."
  [m]
  (let [payload (b64 (.getBytes (pr-str (assoc m :iat (System/currentTimeMillis)))
                                StandardCharsets/UTF_8))]
    (str payload "." (b64 (hmac payload)))))

(defn read-token
  "The map signed into `s` by `token`, provided the signature is ours and the
  token is younger than `max-age` ms; nil otherwise, however malformed."
  [s max-age]
  (try
    (when-let [[_ payload sig] (and (string? s)
                                    (re-matches #"([A-Za-z0-9_-]+)\.([A-Za-z0-9_-]+)" s))]
      (when (MessageDigest/isEqual (hmac payload) (unb64 sig))
        ;; Only signed, so only our own pr-str output ever reaches read-string.
        (let [{:keys [iat] :as m} (edn/read-string (String. (unb64 payload)
                                                            StandardCharsets/UTF_8))]
          (when (and iat (< (- (System/currentTimeMillis) iat) max-age))
            (dissoc m :iat)))))
    ;; Undecodable base64, unreadable EDN: a stranger's forgery, not our bug.
    (catch Exception _
      nil)))

(defn- client-id
  ;; By convention the client is identified by its home page, trailing slash
  ;; included; :url deliberately has none, since paths concatenate onto it.
  ;; auth/our-client-id mints the same string; a require cycle keeps the two
  ;; definitions apart.
  [{:keys [url] :as conf}]
  (str url "/"))

(defn- redirect-uri
  [{:keys [url] :as conf}]
  (str url "/sign-in/callback"))

(defonce state-secret
  ;; Signs the session of a sign-in into its state (see indieauth-clj).
  (indieauth/token))

(defn sign-in-request
  "Where to send the visitor claiming to be `me`, from the post at `path`, to
  the sign-in endpoint of `conf`: a map of the :url, and of the :nonce that
  their browser keeps in a cookie until the callback (see `nonce-cookie`)."
  [{:keys [sign-in] :as conf} me path]
  (let [{:keys [url session]}
        (indieauth/authorization-request
          {:me                     me
           :authorization-endpoint (:endpoint sign-in)}
          {:client-id    (client-id conf)
           ;; The post rides in the redirect URL, which the state signs.
           :redirect-uri (fetch.url/with-query (redirect-uri conf) {:path path})
           :secret       state-secret})]
    {:url   url
     :nonce (:nonce session)}))

(defn sign-in!
  "Finish the sign-in that the callback's query `params` and the browser's
  `nonce` belong to: the verified :me and the :path of the post, or nil when
  the state isn't ours, another browser started it, or the endpoint rejects
  the code."
  [params nonce]
  (when-let [session (indieauth/state-session state-secret (:state params) nonce)]
    (let [{:keys [me error]} (indieauth/redeem! session params {:send http/send!})]
      (if error
        (tel/log! {:level :warn
                   :id    ::sign-in-error
                   :data  error
                   :msg   (str "Could not verify sign-in: " (:reason error))})
        {:me   me
         :path (-> (:redirect-uri session)
                   (str/split #"\?" 2)
                   (second)
                   (fetch.url/query-params)
                   (get "path"))}))))

(def nonce-cookie-name
  "The cookie that keeps the nonce of a sign-in."
  "sign-in")

(defn nonce-cookie
  "The Set-Cookie value that keeps the `nonce` for the callback alone, for as
  long as a sign-in lasts; a nil `nonce` clears it."
  [nonce]
  (str nonce-cookie-name "=" nonce
       "; Path=/sign-in/callback"
       "; Max-Age=" (if nonce (:session-seconds indieauth/default-options) 0)
       "; HttpOnly; SameSite=Lax; Secure"))

(defn nonce
  "The nonce of a sign-in among the cookies of the Ring `request`, or nil."
  [request]
  (some->> (get-in request [:headers "cookie"])
           (re-find (re-pattern (str "(?:^|;\\s*)" nonce-cookie-name "=([^;]+)")))
           (second)))

(comment
  (let [t (token {:me "https://example.com/" :path "/posts/2020/some-post"})]
    [(read-token t comment-max-age)
     (read-token t -1)                                       ; expired => nil
     (read-token (str t "x") comment-max-age)])              ; tampered => nil
  #_.)
