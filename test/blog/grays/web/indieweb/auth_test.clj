(ns blog.grays.web.indieweb.auth-test
  "Tests for the Web sign-in endpoint's checks.

  What is pinned is the security surface: URL canonicalization (identity
  comparison), rel=me discovery, the bidirectional GitHub check, code
  redemption, and that a sign-in finishes only in the browser that started
  it. None of it fails loudly when it is wrong; a sign-in just succeeds for
  the wrong person. See the auth namespace."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [jsonista.core :as json]
            [dk.simongray.indieauth :as indieauth]
            [dk.simongray.indieauth.server :as server]
            [dk.simongray.wary-fetch.secret :as secret]
            [dk.simongray.wary-fetch.url :as fetch.url]
            [blog.grays.web.http :as http]
            [blog.grays.web.indieweb :as indieweb]
            [blog.grays.web.indieweb.micropub :as micropub]
            [blog.grays.web.indieweb.auth :as auth]
            [blog.grays.web.indieweb.signin :as signin]
            [blog.grays.web.indieweb.webmention.html :as html]))

(deftest link-form
  ;; The me itself is canonical as IndieAuth 3.4 has it (indieauth-clj); a
  ;; rel=me link back compares more leniently.
  (testing "scheme and host are normalized; a bare domain is taken as https"
    (is (= (auth/link-form "https://example.com/") (auth/link-form "EXAMPLE.com")))
    (is (= (auth/link-form "http://example.com/") (auth/link-form "http://example.com:80/"))))

  (testing "a trailing slash and a fragment don't matter"
    (is (= (auth/link-form "https://example.com/me") (auth/link-form "https://example.com/me/")))
    (is (= (auth/link-form "https://example.com/") (auth/link-form "https://example.com/#top"))))

  (testing "what is not an http(s) URL is nothing at all"
    (is (nil? (auth/link-form "ftp://example.com/")))
    (is (nil? (auth/link-form "")))
    (is (nil? (auth/link-form nil)))))

(def homepage
  "A homepage claiming several rel=me profiles, one of them GitHub."
  "<html><head><link rel='me' href='/relative'></head>
   <body>
     <a rel='me nofollow' href='https://indieweb.social/@jane'>Mastodon</a>
     <a rel='me' href='https://github.com/JaneDoe'>GitHub</a>
     <a href='https://github.com/not-rel-me'>just a link</a>
   </body></html>")

(deftest rel-me-discovery
  (let [hrefs (html/rel-hrefs (html/parse homepage "https://example.com/") "me")]
    (testing "rel=me hrefs are absolutised and in document order"
      (is (= ["https://example.com/relative"
              "https://indieweb.social/@jane"
              "https://github.com/JaneDoe"]
             (vec hrefs))))

    (testing "the GitHub profile among them yields its username"
      (is (= "JaneDoe" (auth/github-username hrefs)))))

  (testing "a repo link is not a profile"
    (is (nil? (auth/github-username ["https://github.com/janedoe/some-repo"])))))

(deftest indieauth-discovery
  (let [doc (html/parse (str "<link rel='indieauth-metadata' href='/.well-known/indieauth'>"
                             "<a rel='authorization_endpoint' href='https://auth.example.com/authz'>x</a>")
                        "https://example.com/")]
    (testing "rel hrefs are found and absolutised"
      (is (= "https://example.com/.well-known/indieauth"
             (html/rel-href doc "indieauth-metadata")))
      (is (= "https://auth.example.com/authz"
             (html/rel-href doc "authorization_endpoint")))
      (is (nil? (html/rel-href doc "token_endpoint"))))))

(deftest indieauth-callback-checks
  ;; A sign-in delegated to the visitor's own server, as indieauth-start!
  ;; begins it; every check here comes before any exchange.
  (let [{:keys [session]} (indieauth/authorization-request
                            {:me                     "https://example.com/"
                             :authorization-endpoint "https://auth.example.com/authz"
                             :issuer                 "https://auth.example.com/"}
                            {:client-id    "https://simon.grays.blog/"
                             :redirect-uri "https://simon.grays.blog/auth/callback/indieauth"
                             :secret       auth/secret})
        cookie            (str (:nonce session) ".a-request")
        params            {:code  "code"
                           :state (:state session)
                           :iss   "https://auth.example.com/"}]
    (testing "a mismatched or missing iss (RFC 9207) is rejected"
      (is (nil? (auth/indieauth-callback! {} (assoc params :iss "https://evil.example/")
                                          cookie)))
      (is (nil? (auth/indieauth-callback! {} (dissoc params :iss) cookie))))

    (testing "a forged state is rejected"
      (is (nil? (auth/indieauth-callback! {} (update params :state str "x") cookie))))

    (testing "another browser, without the cookie, can't finish it"
      (is (nil? (auth/indieauth-callback! {} params nil)))
      (is (nil? (auth/indieauth-callback! {} params "another-nonce.a-request"))))))

(deftest sign-in-nonce
  ;; Login CSRF (RFC 9700, section 4.7): someone finishing their own sign-in
  ;; in a visitor's browser, who then comments as them.
  (let [conf    {:url     "https://simon.grays.blog"
                 :sign-in {:endpoint "https://simon.grays.blog/auth"}}
        started (signin/sign-in-request conf "https://visitor.example/" "/posts/2020/x")
        state   (-> (:url started) (str/split #"\?" 2) (second)
                    (fetch.url/query-params) (get "state"))
        params  {:code "a-code" :state state}]
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
      (let [cookie (signin/nonce-cookie (:nonce started))]
        (is (str/includes? cookie "; Path=/sign-in/callback;"))
        (is (str/includes? cookie "; HttpOnly; SameSite=Lax; Secure"))
        (is (= (:nonce started)
               (signin/nonce {:headers {"cookie" (str "theme=dark; "
                                                      (first (str/split cookie #";")))}})))))))

(deftest provider-choices
  (let [conf {:url    "https://simon.grays.blog"
              :github {:client-id "id" :client-secret "secret"}}
        doc  (html/parse (str "<a rel='me' href='https://github.com/JaneDoe'>gh</a>"
                              "<a rel='me' href='https://indieweb.social/@jane'>m</a>"
                              "<a rel='me' href='https://pixelfed.dk/jane'>p</a>")
                         "https://example.com/")]
    (testing "every supported provider is offered, in the page's order"
      (is (= [{:kind :github :user "JaneDoe"}
              {:kind     :mastodon
               :instance "https://indieweb.social"
               :user     "jane"
               :href     "https://indieweb.social/@jane"}]
             (auth/discovered-options conf {} doc))))

    (testing "GitHub is not offered without the app's credentials"
      (is (= [:mastodon]
             (map :kind (auth/discovered-options (dissoc conf :github) {} doc)))))

    (testing "a homepage advertising our own endpoint offers only rel=me"
      (let [doc (html/parse (str "<link rel='authorization_endpoint'"
                                 "      href='https://simon.grays.blog/auth'>"
                                 "<a rel='me' href='https://github.com/simongray'>gh</a>")
                            "https://simon.grays.blog/")]
        (is (= [{:kind :github :user "simongray"}]
               (auth/discovered-options conf {} doc)))))))

(deftest mastodon-discovery
  (testing "a Mastodon-style profile URL yields its instance and username"
    (is (= ["https://indieweb.social" "simongray"]
           (rest (re-matches auth/mastodon-profile-re
                             "https://indieweb.social/@simongray")))))

  (testing "other profile shapes do not match"
    (is (nil? (re-matches auth/mastodon-profile-re "https://github.com/simongray")))
    (is (nil? (re-matches auth/mastodon-profile-re "https://example.com/@a/b")))))

(deftest mastodon-links-back
  (let [account {:url    "https://indieweb.social/@jane"
                 :note   "<p>I write at <a href=\"https://example.com/\">my site</a></p>"
                 :fields [{:name  "Website"
                           :value "<a href=\"https://EXAMPLE.com\">example.com</a>"}]}]
    (testing "a link back in the bio or a profile field closes the loop"
      (is (auth/mastodon-links-back? "https://example.com/" account))
      (is (auth/mastodon-links-back? "https://example.com/" (dissoc account :fields)))
      (is (auth/mastodon-links-back? "https://example.com/" (dissoc account :note))))

    (testing "no link back, no sign-in"
      (is (not (auth/mastodon-links-back? "https://example.com/"
                                          {:note "<p>nothing here</p>" :fields []})))
      (is (not (auth/mastodon-links-back? "https://elsewhere.example/" account))))))

(deftest github-links-back
  (testing "the profile's blog field is compared canonically"
    (is (auth/github-links-back? "https://example.com/" {:blog "example.com"}))
    (is (auth/github-links-back? "https://example.com/" {:blog "https://EXAMPLE.com/"}))
    (is (auth/github-links-back? "https://example.com/blog/" {:blog "example.com/blog"})))

  (testing "no or another website is no link back"
    (is (not (auth/github-links-back? "https://example.com/" {:blog "https://elsewhere.com/"})))
    (is (not (auth/github-links-back? "https://example.com/" {:blog ""})))
    (is (not (auth/github-links-back? "https://example.com/" {})))))

;;; Our IndieAuth server, end to end. Codes and tokens are kept in memory,
;;; and no client is fetched.

(def server-conf
  "Our site's conf, as far as the server needs it."
  {:url       "https://simon.grays.blog"
   :author    "Simon Gray"
   :sign-in   {:endpoint "https://simon.grays.blog/auth"}
   :indieauth {:metadata               "https://simon.grays.blog/auth/metadata"
               :authorization-endpoint "https://simon.grays.blog/auth"
               :token-endpoint         "https://simon.grays.blog/auth/token"}})

(defn query
  "The query parameters of `url`, by name."
  [url]
  (fetch.url/query-params (second (str/split url #"\?" 2))))

(defn checked-request
  "The authorization request at `url` as our server checks it."
  [url]
  (:request (server/authorization-request!
              (query url)
              (assoc (auth/server-opts server-conf) :client-info? false))))

(defn local-auth
  "Our /auth answering a POST of the request map `request` in-process, as
  wary-fetch would have sent it."
  [{:keys [body] :as request}]
  ((server/handler (assoc (auth/server-opts server-conf) :authorize (constantly nil)))
   {:request-method :post
    :form-params    (query (str "?" (secret/reveal body)))}))

(deftest visitor-sign-in
  (with-redefs [indieweb/token-store (constantly (server/memory-store))]
    (let [started  (signin/sign-in-request server-conf "https://visitor.example/"
                                           "/posts/2020/x")
          request  (checked-request (:url started))
          answered (auth/signed-in! server-conf "https://visitor.example/"
                                    (server/request-token request
                                                          (auth/server-opts server-conf)))
          params   (update-keys (query (:redirect answered)) keyword)]
      (testing "our own blog's request gets its code at once, with our issuer"
        (is (str/starts-with? (:redirect answered)
                              "https://simon.grays.blog/sign-in/callback?path="))
        (is (= "https://simon.grays.blog/" (:iss params))))

      (testing "the code redeems at /auth, with PKCE, for the visitor"
        (with-redefs [http/send! local-auth]
          (is (= {:me "https://visitor.example/" :path "/posts/2020/x"}
                 (signin/sign-in! params (:nonce started)))))))))

(deftest client-authorization
  (with-redefs [indieweb/token-store (constantly (server/memory-store))]
    (let [opts     (auth/server-opts server-conf)
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
        (is (nil? (auth/signed-in! server-conf "https://visitor.example/" token))))

      (let [{:keys [consent]}  (auth/signed-in! server-conf "https://simon.grays.blog/" token)
            {:keys [redirect]} (auth/approve! server-conf (:token consent))
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
                req     {:conf    server-conf
                         :headers {"authorization" (str "Bearer " access)}}]
            (is (nil? (micropub/authorize req #{"create" "post"})))
            (is (= 403 (:status (micropub/authorize req #{"delete"}))))
            (is (= 401 (:status (micropub/authorize (assoc-in req [:headers "authorization"]
                                                              "Bearer forged")
                                                    #{"create"}))))))))))

(deftest own-client
  (let [conf {:url "https://simon.grays.blog"}]
    (is (auth/own-client? conf
                          "https://simon.grays.blog/"
                          "https://simon.grays.blog/sign-in/callback"))
    (is (not (auth/own-client? conf
                               "https://evil.example/"
                               "https://simon.grays.blog/sign-in/callback")))
    (is (not (auth/own-client? conf
                               "https://simon.grays.blog/"
                               "https://evil.example/")))
    (testing "a look-alike domain sharing our URL as a prefix is not us"
      (is (not (auth/own-client? conf
                                 "https://simon.grays.blog.evil.example/"
                                 "https://simon.grays.blog.evil.example/cb"))))
    (is (not (auth/own-client? conf nil nil)))))
