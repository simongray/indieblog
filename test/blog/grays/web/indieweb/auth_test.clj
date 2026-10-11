(ns blog.grays.web.indieweb.auth-test
  "Tests for signing in, and for our IndieAuth server.

  What is pinned is how we use indieauth-clj and relmeauth-clj, whose own
  tests cover the protocols: which ways in a homepage offers, that a sign-in
  finishes only in the browser that started it, and the server end to end.
  None of it fails loudly when it is wrong; a sign-in just succeeds for the
  wrong person. See the auth namespace."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [jsonista.core :as json]
            [dk.simongray.indieauth :as indieauth]
            [dk.simongray.indieauth.server :as server]
            [dk.simongray.relmeauth :as relmeauth]
            [dk.simongray.wary-fetch.secret :as secret]
            [dk.simongray.wary-fetch.url :as fetch.url]
            [blog.grays.web.http :as http]
            [blog.grays.web.indieweb :as indieweb]
            [blog.grays.web.indieweb.micropub :as micropub]
            [blog.grays.web.indieweb.auth :as auth]
            [blog.grays.web.indieweb.signin :as signin]))

(def conf
  "Our site's conf, as far as signing in and the server need it."
  {:url          "https://simon.grays.blog"
   :author       "Simon Gray"
   :indieweb-dir "/tmp/indieblog-auth-test"
   :github       {:client-id "id" :client-secret "secret"}
   :sign-in      {:endpoint "https://simon.grays.blog/auth"}
   :indieauth    {:metadata               "https://simon.grays.blog/auth/metadata"
                  :authorization-endpoint "https://simon.grays.blog/auth"
                  :token-endpoint         "https://simon.grays.blog/auth/token"}})

(defn query
  "The query parameters of `url`, by name."
  [url]
  (fetch.url/query-params (second (str/split url #"\?" 2))))

(defn page
  "The response of the homepage at https://example.com/ with the HTML `body`."
  [body]
  {:status 200 :url "https://example.com/" :headers {"content-type" "text/html"} :body body})

(defn ways-in
  "The ways in that the homepage with the HTML `body` offers under `conf`, as
  begin! finds them, to the visitor `me`, https://example.com/ by default."
  ([conf body]
   (ways-in conf body "https://example.com/"))
  ([conf body me]
   (let [response (page body)]
     (auth/options conf response
                   (relmeauth/discovery me response (#'auth/relme-opts conf))))))

(deftest options
  (let [rel-me (str "<a rel='me' href='https://github.com/JaneDoe'>gh</a>"
                    "<a rel='me' href='https://indieweb.social/@jane'>m</a>"
                    "<a rel='me' href='https://pixelfed.dk/jane'>p</a>")]
    (testing "the accounts of supported providers, in the page's order"
      (is (= [[:github "JaneDoe"] [:mastodon "jane"]]
             (map (juxt :provider :username) (ways-in conf rel-me)))))

    (testing "GitHub is not offered without the app's credentials"
      (is (= [:mastodon]
             (map :provider (ways-in (dissoc conf :github) rel-me)))))

    (testing "no accounts for a visitor whose URL is itself an account at one"
      (is (empty? (ways-in conf rel-me "https://github.com/JaneDoe"))))

    (testing "the visitor's own IndieAuth server comes first, but never ours"
      (is (= [:indieauth :github :mastodon]
             (map :provider (ways-in conf (str "<link rel='authorization_endpoint'"
                                               " href='https://example.com/auth'>"
                                               rel-me)))))
      (is (= [:github :mastodon]
             (map :provider (ways-in conf (str "<link rel='authorization_endpoint'"
                                               " href='https://simon.grays.blog/auth'>"
                                               rel-me))))))))

(deftest callback-checks
  ;; A sign-in at the visitor's own server, as start! begins it; every check
  ;; here comes before any exchange.
  (let [{:keys [session]} (indieauth/authorization-request
                            {:me                     "https://example.com/"
                             :authorization-endpoint "https://auth.example.com/authz"
                             :issuer                 "https://auth.example.com/"}
                            {:client-id    "https://simon.grays.blog/"
                             :redirect-uri "https://simon.grays.blog/auth/callback/indieauth"
                             :secret       signin/secret
                             :data         "a-request"})
        nonce             (:nonce session)
        params            {:code  "code"
                           :state (:state session)
                           :iss   "https://auth.example.com/"}
        callback!         #(auth/callback! conf :indieauth %1 %2)]
    (testing "a mismatched or missing iss (RFC 9207) is rejected"
      (is (nil? (callback! (assoc params :iss "https://evil.example/") nonce)))
      (is (nil? (callback! (dissoc params :iss) nonce))))

    (testing "a forged state is rejected"
      (is (nil? (callback! (update params :state str "x") nonce))))

    (testing "another browser, without the nonce, can't finish it"
      (is (nil? (callback! params nil)))
      (is (nil? (callback! params "another-nonce"))))))

(deftest sign-in-nonce
  ;; Login CSRF (RFC 9700, section 4.7): someone finishing their own sign-in
  ;; in a visitor's browser, who then comments as them.
  (let [started (signin/sign-in-request conf "https://visitor.example/" "/posts/2020/x")
        params  {:code "a-code" :state (get (query (:url started)) "state")}]
    ;; The endpoint vouches for the visitor, whoever asks.
    (with-redefs [http/send! (constantly {:status  200
                                          :headers {"content-type" "application/json"}
                                          :body    "{\"me\":\"https://visitor.example/\"}"})]
      (testing "the browser that started the sign-in finishes it"
        (is (= {:me "https://visitor.example/" :path "/posts/2020/x"}
               (signin/sign-in! params (:nonce started)))))

      (testing "another browser can't, with another nonce or none"
        (is (nil? (signin/sign-in! params "another-nonce")))
        (is (nil? (signin/sign-in! params nil)))))

    (testing "the nonce goes in a cookie for the callback alone"
      (let [cookie (signin/cookie signin/sign-in-cookie (:nonce started))]
        (is (str/includes? cookie "; Path=/sign-in/callback;"))
        (is (str/includes? cookie "; HttpOnly; SameSite=Lax; Secure"))
        (is (= (:nonce started)
               (signin/cookie-value {:headers {"cookie" (str "theme=dark; "
                                                             (first (str/split cookie #";")))}}
                                    signin/sign-in-cookie)))))))

(deftest comment-token
  (let [token (signin/comment-token "https://visitor.example/" "/posts/2020/x")]
    (is (= {:me "https://visitor.example/" :path "/posts/2020/x"}
           (signin/read-comment-token token)))
    (is (nil? (signin/read-comment-token (str token "x"))))
    (is (nil? (signin/read-comment-token nil)))))

;;; Our IndieAuth server, end to end. Codes and tokens are kept in memory,
;;; and no client is fetched.

(defn checked-request
  "The authorization request at `url` as our server checks it."
  [url]
  (:request (server/authorization-request!
              (query url)
              (assoc (auth/server-opts conf) :client-info? false))))

(defn local-auth
  "Our /auth answering a POST of the request map `request` in-process, as
  wary-fetch would have sent it."
  [{:keys [body] :as request}]
  ((server/handler (assoc (auth/server-opts conf) :authorize (constantly nil)))
   {:request-method :post
    :form-params    (query (str "?" (secret/reveal body)))}))

(deftest visitor-sign-in
  (with-redefs [indieweb/token-store (constantly (server/memory-store))]
    (let [started  (signin/sign-in-request conf "https://visitor.example/" "/posts/2020/x")
          request  (checked-request (:url started))
          answered (auth/signed-in! conf "https://visitor.example/"
                                    (server/request-token request (auth/server-opts conf)))
          params   (update-keys (query (:redirect answered)) keyword)]
      (testing "our own blog's request gets its code at once, with our issuer"
        (is (str/starts-with? (:redirect answered) "https://simon.grays.blog/sign-in/callback?"))
        (is (= "https://simon.grays.blog/" (:iss params))))

      (testing "the code redeems at /auth, with PKCE, for the visitor"
        (with-redefs [http/send! local-auth]
          (is (= {:me "https://visitor.example/" :path "/posts/2020/x"}
                 (signin/sign-in! params (:nonce started)))))))))

(deftest client-authorization
  (with-redefs [indieweb/token-store (constantly (server/memory-store))]
    (let [opts     (auth/server-opts conf)
          verifier (indieauth/code-verifier)
          request  (checked-request
                     (fetch.url/with-query
                       "https://simon.grays.blog/auth"
                       {:response_type         "code"
                        :client_id             "https://quill.p3k.io/"
                        :redirect_uri          "https://quill.p3k.io/redirect"
                        :state                 "quill-state"
                        :code_challenge        (indieauth/code-challenge verifier)
                        :code_challenge_method "S256"
                        :scope                 "create update"}))
          token    (server/request-token request opts)]
      (testing "only the site's owner may answer another client's request"
        (is (nil? (auth/signed-in! conf "https://visitor.example/" token))))

      (let [{:keys [consent]}  (auth/signed-in! conf "https://simon.grays.blog/" token)
            {:keys [redirect]} (auth/approve! conf (:token consent))
            params             (query redirect)]
        (testing "the owner is asked, and their yes gives the client a code"
          (is (= ["create" "update"] (:scopes consent)))
          (is (str/starts-with? redirect "https://quill.p3k.io/redirect?"))
          (is (= "quill-state" (get params "state")))
          (is (= "https://simon.grays.blog/" (get params "iss"))))

        (testing "the code buys a token that Micropub takes, for its scopes alone"
          (let [{:keys [body]} ((server/token-handler opts)
                                {:request-method :post
                                 :form-params    {"grant_type"    "authorization_code"
                                                  "code"          (get params "code")
                                                  "client_id"     "https://quill.p3k.io/"
                                                  "redirect_uri"  "https://quill.p3k.io/redirect"
                                                  "code_verifier" verifier}})
                access  (get (json/read-value body) "access_token")
                req     {:conf    conf
                         :headers {"authorization" (str "Bearer " access)}}]
            (is (nil? (micropub/authorize req #{"create" "post"})))
            (is (= 403 (:status (micropub/authorize req #{"delete"}))))
            (is (= 401 (:status (micropub/authorize (assoc-in req [:headers "authorization"]
                                                              "Bearer forged")
                                                    #{"create"}))))))))))

(deftest own-request
  (let [own? #(auth/own-request? conf {:client-id %1 :redirect-uri %2})]
    (is (own? "https://simon.grays.blog/" "https://simon.grays.blog/sign-in/callback"))
    (is (not (own? "https://evil.example/" "https://simon.grays.blog/sign-in/callback")))
    (is (not (own? "https://simon.grays.blog/" "https://evil.example/")))
    (testing "a look-alike domain sharing our URL as a prefix is not us"
      (is (not (own? "https://simon.grays.blog.evil.example/"
                     "https://simon.grays.blog.evil.example/cb"))))
    (is (not (own? nil nil)))))
