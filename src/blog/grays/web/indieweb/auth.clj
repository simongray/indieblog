(ns blog.grays.web.indieweb.auth
  "Our own IndieAuth server, on indieauth-clj, and the ways a person signs in
  to it: through their own IndieAuth server, or through a GitHub or Mastodon
  account that their homepage links with rel=me (RelMeAuth, on
  relmeauth-clj).

  Our own blog asks the server for a visitor, who gets their code at once.
  Any other client, e.g. a Micropub app, asks for the site's owner, who
  consents too. The server's signed request rides through the sign-in in
  its state, and a cookie for the callbacks ties the sign-in to the browser
  that started it."
  (:require [clojure.string :as str]
            [taoensso.telemere :as tel]
            [dk.simongray.indieauth :as indieauth]
            [dk.simongray.indieauth.server :as server]
            [dk.simongray.relmeauth :as relmeauth]
            [dk.simongray.wary-fetch.secret :as secret]
            [blog.grays.web.http :as http]
            [blog.grays.web.shared :as shared]
            [blog.grays.web.indieweb :as indieweb]
            [blog.grays.web.indieweb.signin :as signin]))

;;; The server

(defonce codes
  ;; A code lasts a minute, so memory is enough.
  (server/memory-store))

(defn server-opts
  "The options of indieauth-clj's server under `conf`."
  [{:keys [indieauth indieweb-dir] :as conf}]
  {:issuer                 (signin/client-id conf)
   :metadata-url           (:metadata indieauth)
   :authorization-endpoint (:authorization-endpoint indieauth)
   :token-endpoint         (:token-endpoint indieauth)
   :revocation-endpoint    (:revocation-endpoint indieauth)
   ;; What Micropub acts on, besides the profile; see micropub/opts.
   :scopes-supported       ["profile" "email" "create" "update" "delete" "media"]
   ;; Micropub clients seldom refresh a token, so one lasts until it's
   ;; revoked, as ours always have.
   :token-seconds          nil
   :refresh-seconds        nil
   :secret                 signin/secret
   :codes                  codes
   :tokens                 (indieweb/token-store indieweb-dir)})

(defn own-request?
  "Is the checked authorization `request` our own blog's under `conf`, i.e.
  a visitor's Web sign-in?"
  [{:keys [url] :as conf} {:keys [client-id redirect-uri] :as request}]
  ;; The prefix includes the boundary slash, or a look-alike domain
  ;; (ours.evil.example) would pass as us.
  (every? #(str/starts-with? (str %) (str url "/")) [client-id redirect-uri]))

(defn- owner-profile
  "The profile of the site's owner under `conf`, which a client gets with
  the profile scope."
  [{:keys [author email photo url] :as conf}]
  (shared/compact {:name  author
                   :url   (signin/client-id conf)
                   :photo (some->> photo (str url))
                   :email email}))

(defn signed-in!
  "Carry on with the server's signed `request` now that `me` has signed in
  under `conf`: our own blog's request gets its code at once, as
  {:redirect ..}, and another client's needs the site's owner, who is
  shown the consent page of {:consent ..}. Nil when `me` may not answer."
  [conf me request]
  (let [opts (server-opts conf)
        me   (indieauth/canonical-url me)]
    (when-let [request (and me
                            (indieauth/profile-url? me)
                            (server/token-request request opts))]
      (cond
        (own-request? conf request)
        (server/approve! (:token (server/consent request me opts)) {:me me} opts)

        (= me (signin/client-id conf))
        {:consent (server/consent request me opts)}))))

(defn approve!
  "Give the client its code now that the owner has said yes on the consent
  page, whose signed `token` they posted under `conf`: {:redirect ..}, or
  {:error ..}."
  [conf token]
  (server/approve! token
                   {:me      (signin/client-id conf)
                    :profile (owner-profile conf)}
                   (server-opts conf)))

;;; Signing in

(def callback-cookie
  "The cookie that keeps a sign-in for the provider's callback."
  {:name "auth" :path "/auth/callback"})

(defn- callback-uri
  [{:keys [url] :as conf} provider]
  (str url "/auth/callback/" (name provider)))

(defn- relme-opts
  "The options of relmeauth-clj under `conf`, for the providers it sets up:
  GitHub, when `conf` has the :github app's credentials, and Mastodon."
  [{:keys [github indieweb-dir name url] :as conf}]
  (cond-> (assoc http/opts
                 :mastodon {:registrations (indieweb/registration-store indieweb-dir)
                            :client-name   name
                            :website       (str url "/")}
                 :secret   signin/secret)
    github (assoc :github (update github :client-secret secret/hide))))

(defn options
  "Every way the page in the `response` says its owner can sign in under
  `conf`, in order: their own IndieAuth server first, unless it's ours, then
  each account of the RelMeAuth `discovery` of the page."
  [conf response {:keys [accounts] :as discovery}]
  (let [{:keys [metadata-url authorization-endpoint]} (indieauth/endpoints response)
        endpoint (or metadata-url authorization-endpoint)]
    (concat (when (and endpoint (not (str/starts-with? endpoint (str (:url conf) "/"))))
              [{:provider :indieauth :endpoint endpoint}])
            accounts)))

(defn- start!
  "How the sign-in of `me`, whose page has the RelMeAuth `discovery`, starts
  at the `option` under `conf`, carrying the server's signed `request`:
  {:redirect .. :cookie ..}, or nil when it can't, e.g. at a Mastodon server
  that won't register us."
  [conf me discovery {:keys [provider] :as option} request]
  (let [opts {:redirect-uri (callback-uri conf provider)
              :secret       signin/secret
              :data         request}
        {:keys [url session error]}
        (if (= :indieauth provider)
          (let [discovery (indieauth/discover! me http/opts)]
            (if (:error discovery)
              discovery
              (indieauth/authorization-request
                discovery (assoc opts :client-id (signin/client-id conf)))))
          (relmeauth/authorization-request! discovery option (merge (relme-opts conf) opts)))]
    (if error
      (do (tel/log! {:level :info
                     :id    ::unavailable
                     :data  (assoc error :me me)
                     :msg   (str "Can't sign " me " in at " (name provider) ": " (:reason error))})
          nil)
      {:redirect url
       :cookie   (signin/cookie callback-cookie (:cookie session))})))

(defn begin!
  "Begin signing in whoever the checked authorization `request` of our
  server needs under `conf`: the visitor it names as me, for our own blog,
  or else the site's owner. Gives {:redirect .. :cookie ..}, {:choices ..}
  when their homepage offers several ways and the `provider` picks none,
  or nil when they can't sign in."
  [conf request & {:keys [provider]}]
  (when-let [me (indieauth/canonical-url (if (own-request? conf request)
                                           (:me request)
                                           (signin/client-id conf)))]
    (when-let [response (http/page! me)]
      (let [token     (server/request-token request (server-opts conf))
            discovery (relmeauth/discovery me response (relme-opts conf))
            options   (cond->> (options conf response discovery)
                        provider (filter #(= provider (name (:provider %)))))]
        (if (and (next options) (not provider))
          {:choices options}
          (some #(start! conf me discovery % token) options))))))

(defn callback!
  "Finish the sign-in at `provider` that the callback's query `params` and
  the browser's `cookie` belong to, under `conf`: what signed-in! gives, or
  nil when the state isn't ours, another browser started it, or the
  provider or the account fails a check, logged ::rejected."
  [conf provider params cookie]
  (let [indieauth? (= :indieauth provider)
        session    ((if indieauth? indieauth/state-session relmeauth/state-session)
                    signin/secret (:state params) cookie)
        {:keys [me error]}
        (cond
          (nil? session) {:error {:reason "Not this browser's sign-in"}}
          indieauth?     (indieauth/redeem! session params http/opts)
          :else          (relmeauth/redeem! session params (relme-opts conf)))]
    (if me
      (signed-in! conf me (:data session))
      (do (tel/log! {:level :info
                     :id    ::rejected
                     :data  (assoc error :me (:me session))
                     :msg   (str "Sign-in rejected" (some->> (:me session) (str " for "))
                                 ": " (:reason error))})
          nil))))

(comment
  (require '[blog.grays.web.service :as service])

  ;; Discovery, live: a homepage advertising its own IndieAuth server wins
  ;; over rel=me; our own homepage advertises *us*, so the owner falls
  ;; through to their accounts instead of looping.
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
