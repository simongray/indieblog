(ns blog.grays.web.indieweb.signin
  "Web sign-in for visitors (https://indieweb.org/Web_sign-in): a visitor
  claims their website URL, the :sign-in :endpoint of conf authenticates the
  claim, and we learn a verified site to attribute their comment to.

  In practice the endpoint is our own /auth, served by the auth namespace
  (doc/plan-auth.md); this namespace is its client, unchanged from the days
  when the endpoint was IndieLogin.com. The flow is the classic one: send the
  visitor to the endpoint with me/client_id/redirect_uri/state, receive
  code+state on the callback, POST the code back, and read the verified site
  out of the JSON response.

  There are no sessions and no cookies. Continuity is carried by the
  short-lived HMAC-signed tokens minted here: first the state round-tripped
  through the endpoint, then a fresh token on the comment form proving that
  the callback authenticated its bearer. The secret is random per boot;
  restarting merely voids in-flight sign-ins."
  (:require [clojure.edn :as edn]
            [jsonista.core :as json]
            [taoensso.telemere :as tel]
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
  [{:keys [url] :as conf}]
  (str url "/"))

(defn- redirect-uri
  [{:keys [url] :as conf}]
  (str url "/sign-in/callback"))

(defn auth-url
  "The URL at the sign-in endpoint of `conf` to send the visitor claiming to
  be `me` to, carrying our signed `state`."
  [{:keys [sign-in] :as conf} me state]
  (str (:endpoint sign-in)
       "?" (http/form-encode {:me           me
                              :client_id    (client-id conf)
                              :redirect_uri (redirect-uri conf)
                              :state        state})))

(defn exchange-code!
  "Verify the callback's `code` against the sign-in endpoint of `conf`,
  returning the authenticated site URL, or nil when the endpoint rejects it."
  [{:keys [sign-in] :as conf} code]
  (try
    (let [response (http/post-form! (:endpoint sign-in)
                                    {:code         code
                                     :client_id    (client-id conf)
                                     :redirect_uri (redirect-uri conf)}
                                    {"Accept" "application/json"})]
      (when (http/ok? response)
        (:me (json/read-value (:body response) json/keyword-keys-object-mapper))))
    (catch Exception e
      (tel/log! {:level :warn
                 :id    ::exchange-error
                 :msg   (str "Could not verify sign-in code: " (ex-message e))})
      nil)))

(comment
  (let [t (token {:me "https://example.com/" :path "/posts/2020/some-post"})]
    [(read-token t comment-max-age)
     (read-token t -1)                                       ; expired => nil
     (read-token (str t "x") comment-max-age)])              ; tampered => nil
  #_.)
