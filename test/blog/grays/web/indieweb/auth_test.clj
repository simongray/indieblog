(ns blog.grays.web.indieweb.auth-test
  "Tests for the Web sign-in endpoint's checks.

  What is pinned is the security surface: URL canonicalization (identity
  comparison), rel=me discovery, the bidirectional GitHub check, and code
  redemption. None of it fails loudly when it is wrong; a sign-in just
  succeeds for the wrong person. See the auth namespace."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [blog.grays.web.indieweb.auth :as auth]
            [blog.grays.web.indieweb.signin :as signin]
            [blog.grays.web.indieweb.webmention.html :as html]))

(deftest canonical-url
  (testing "scheme and host are normalized; a bare domain is taken as https"
    (is (= "https://example.com/" (auth/canonical-url "EXAMPLE.com")))
    (is (= "https://example.com/" (auth/canonical-url "https://Example.COM/")))
    (is (= "http://example.com/" (auth/canonical-url "http://example.com:80/"))))

  (testing "paths compare slash-insensitively, except the root"
    (is (= "https://example.com/me" (auth/canonical-url "https://example.com/me/")))
    (is (= "https://example.com/" (auth/canonical-url "https://example.com"))))

  (testing "fragments are dropped"
    (is (= "https://example.com/" (auth/canonical-url "https://example.com/#top"))))

  (testing "what is not an http(s) URL is nothing at all"
    (is (nil? (auth/canonical-url "ftp://example.com/")))
    (is (nil? (auth/canonical-url "")))
    (is (nil? (auth/canonical-url nil)))))

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
  (let [state (signin/token {:me           "https://example.com/"
                             :endpoint     "https://auth.example.com/authz"
                             :issuer       "https://auth.example.com/"
                             :client-id    "https://simon.grays.blog/"
                             :redirect-uri "https://simon.grays.blog/sign-in/callback"})]
    (testing "a mismatched or missing iss (RFC 9207) is rejected before any exchange"
      (is (nil? (auth/indieauth-callback! {} "code" state "https://evil.example/")))
      (is (nil? (auth/indieauth-callback! {} "code" state nil))))

    (testing "a forged state is rejected"
      (is (nil? (auth/indieauth-callback! {} "code" (str state "x")
                                          "https://auth.example.com/")))))

  (testing "the PKCE verifier re-derives from the state alone"
    (is (= (signin/signature "some-state") (signin/signature "some-state")))
    (is (not= (signin/signature "some-state") (signin/signature "other-state")))))

(deftest provider-choices
  (let [conf {:url "https://simon.grays.blog"}
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
    (is (auth/github-links-back? "https://example.com/" {:blog "https://EXAMPLE.com/"})))

  (testing "no or another website is no link back"
    (is (not (auth/github-links-back? "https://example.com/" {:blog "https://elsewhere.com/"})))
    (is (not (auth/github-links-back? "https://example.com/" {:blog ""})))
    (is (not (auth/github-links-back? "https://example.com/" {})))))

(deftest redeem
  (let [client   "https://simon.grays.blog/"
        callback "https://simon.grays.blog/sign-in/callback"
        ->code   (fn [me] (signin/token {:kind         :code
                                         :me           me
                                         :client-id    client
                                         :redirect-uri callback}))]
    (testing "a code redeems exactly once, for the client it was issued to"
      (let [code (->code "https://redeem-once.example/")]
        (is (= "https://redeem-once.example/"
               (:me (auth/redeem! code client callback nil))))
        (is (nil? (auth/redeem! code client callback nil)))))

    (testing "a code is bound to its client"
      (is (nil? (auth/redeem! (->code "https://wrong-client.example/")
                              "https://evil.example/" callback nil))))

    (testing "tampering voids the signature"
      (is (nil? (auth/redeem! (str (->code "https://tampered.example/") "x")
                              client callback nil))))

    (testing "a token of another kind is no authorization code"
      (is (nil? (auth/redeem! (signin/token {:me           "https://kindless.example/"
                                             :client-id    client
                                             :redirect-uri callback})
                              client callback nil))))))

(deftest server-role-round-trip
  (let [conf     {:url "https://simon.grays.blog"}
        verifier "test-verifier-test-verifier-test-verifier-43"
        request  (auth/authorization-request
                   conf {:response_type         "code"
                         :client_id             "https://quill.p3k.io/"
                         :redirect_uri          "https://quill.p3k.io/redirect"
                         :state                 "quill-state"
                         :code_challenge        (auth/s256-challenge verifier)
                         :code_challenge_method "S256"
                         :scope                 "create update"})
        ;; What the owner's completed sign-in leg mints (see client-redirect).
        sign-in  (signin/token {:kind         :code
                                :me           "https://simon.grays.blog/"
                                :client-id    "https://simon.grays.blog/"
                                :redirect-uri "https://simon.grays.blog/auth/consent"})
        consent  (auth/consent-request conf sign-in request)
        approved (auth/approve! conf (:token consent))
        code     (some->> approved
                          (re-find #"[?&]code=([^&]+)")
                          (second)
                          (java.net.URLDecoder/decode))]
    (testing "a same-origin redirect_uri with an S256 challenge is accepted"
      (is (some? request)))

    (testing "the owner's sign-in unlocks the consent page for the client"
      (is (= "https://quill.p3k.io/" (:client-id consent)))
      (is (= "create update" (:scope consent))))

    (testing "approval redirects to the client with code, state and iss"
      (is (str/starts-with? approved "https://quill.p3k.io/redirect?"))
      (is (str/includes? approved "state=quill-state"))
      (is (str/includes? approved "iss=https%3A%2F%2Fsimon.grays.blog%2F")))

    (testing "a wrong PKCE verifier is rejected, burning the code with it"
      (let [burned (signin/token {:kind         :code
                                  :me           "https://simon.grays.blog/"
                                  :client-id    "https://quill.p3k.io/"
                                  :redirect-uri "https://quill.p3k.io/redirect"
                                  :challenge    (auth/s256-challenge verifier)
                                  :scope        "create"})]
        (is (nil? (auth/redeem! burned "https://quill.p3k.io/"
                                "https://quill.p3k.io/redirect" "wrong-verifier")))
        (is (nil? (auth/redeem! burned "https://quill.p3k.io/"
                                "https://quill.p3k.io/redirect" verifier)))))

    (testing "the approved code redeems with the matching PKCE verifier"
      (let [m (auth/redeem! code "https://quill.p3k.io/"
                            "https://quill.p3k.io/redirect" verifier)]
        (is (= "https://simon.grays.blog/" (:me m)))
        (is (= "create update" (:scope m))))))

  (testing "a request without a redirect_uri is refused"
    (is (nil? (auth/authorization-request
                {:url "https://simon.grays.blog"}
                {:response_type         "code"
                 :client_id             "https://quill.p3k.io/"
                 :code_challenge        "x"
                 :code_challenge_method "S256"}))))

  (testing "a request without PKCE is refused"
    (is (nil? (auth/authorization-request
                {:url "https://simon.grays.blog"}
                {:response_type "code"
                 :client_id     "https://quill.p3k.io/"
                 :redirect_uri  "https://quill.p3k.io/redirect"})))))

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
