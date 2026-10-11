(ns blog.grays.web.indieweb.signin
  "Web sign-in for visitors (https://indieweb.org/Web_sign-in): a visitor
  claims their website URL, the :sign-in :endpoint of conf (in practice our
  own /auth, served by the auth namespace) authenticates the claim, and we
  learn a verified site to attribute their comment to.

  There are no sessions. Continuity is carried by short-lived signed texts,
  and by a cookie for the callback, which keeps the nonce that ties a
  sign-in to the browser that started it (RFC 9700, section 4.7). The
  secret is random per boot; restarting merely voids sign-ins under way."
  (:require [taoensso.telemere :as tel]
            [dk.simongray.indieauth :as indieauth]
            [dk.simongray.indieauth.signed :as signed]
            [blog.grays.web.http :as http]))

(defonce secret
  ;; Signs every state and token of ours, our IndieAuth server's included.
  (indieauth/token))

(defn client-id
  "Our blog as a client under the :url of `conf`: its home page, trailing
  slash included. Also our issuer, and the owner's profile URL."
  [{:keys [url] :as conf}]
  (str url "/"))

(defn- redirect-uri
  [{:keys [url] :as conf}]
  (str url "/sign-in/callback"))

(defn sign-in-request
  "Where to send the visitor claiming to be `me`, from the post at `path`, to
  the sign-in endpoint of `conf`: a map of the :url, and of the :nonce that
  their browser keeps in a cookie until the callback."
  [{:keys [sign-in] :as conf} me path]
  (let [{:keys [url session]}
        (indieauth/authorization-request
          {:me                     me
           :authorization-endpoint (:endpoint sign-in)}
          {:client-id    (client-id conf)
           :redirect-uri (redirect-uri conf)
           :secret       secret
           :data         path})]
    {:url   url
     :nonce (:nonce session)}))

(defn sign-in!
  "Finish the sign-in that the callback's query `params` and the browser's
  `nonce` belong to: the verified :me and the :path of the post, or nil when
  the state isn't ours, another browser started it, or the endpoint rejects
  the code."
  [params nonce]
  (when-let [session (indieauth/state-session secret (:state params) nonce)]
    (let [{:keys [me error]} (indieauth/redeem! session params http/opts)]
      (if error
        (do (tel/log! {:level :warn
                       :id    ::sign-in-error
                       :data  error
                       :msg   (str "Could not verify sign-in: " (:reason error))})
            nil)
        {:me me :path (:data session)}))))

(def comment-seconds
  "How long a signed-in visitor gets to write their comment."
  (* 30 60))

(defn comment-token
  "A signed text saying that `me` signed in from the post at `path`, which
  the comment form carries."
  [me path]
  (signed/sign secret :comment {:me me :path path}))

(defn read-comment-token
  "The :me and :path of the comment `token`, or nil when it's too old, forged
  or of another kind."
  [token]
  (signed/unsign secret :comment token comment-seconds))

(def sign-in-cookie
  "The cookie that keeps the nonce of a sign-in for its callback."
  {:name "sign-in" :path "/sign-in/callback"})

(defn cookie
  "The Set-Cookie value that keeps `value` in the `cookie` for its path
  alone, for as long as a sign-in lasts; a nil `value` clears it."
  [{:keys [name path] :as cookie} value]
  (str name "=" value
       "; Path=" path
       "; Max-Age=" (if value (:session-seconds indieauth/default-options) 0)
       "; HttpOnly; SameSite=Lax; Secure"))

(defn cookie-value
  "The value of the `cookie` among those of the Ring `request`, or nil."
  [request {:keys [name] :as cookie}]
  (some->> (get-in request [:headers "cookie"])
           (re-find (re-pattern (str "(?:^|;\\s*)" name "=([^;]+)")))
           (second)))

(comment
  (let [t (comment-token "https://example.com/" "/posts/2020/some-post")]
    [(read-comment-token t)
     (read-comment-token (str t "x"))])                      ; tampered => nil
  #_.)
