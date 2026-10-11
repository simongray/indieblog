(ns blog.grays.web.indieweb.webmention-test
  "Tests for what we keep of a Webmention.

  Verifying a source and reading what it shows are webmention-clj's, with
  the scenarios of webmention.rocks among its tests. What is pinned here is
  what we make of its reports, since a mistake there fails silently, on the
  post's page: a mention that shows what it shouldn't, or doesn't vanish
  when its source does."
  (:require [clojure.test :refer [deftest testing is]]
            [blog.grays.web.http :as http]
            [blog.grays.web.indieweb :as indieweb]
            [blog.grays.web.indieweb.webmention :as wm])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def target
  "Our post, the target of every Webmention here."
  "https://simon.grays.blog/posts/2020/x")

(defn response
  "A response to our post by Jane, marked up with the mf2 class `kind-class`,
  which is also in reply to another page."
  [kind-class]
  (str "<article class='h-entry'>"
       "<a class='p-author h-card' href='https://jane.example/'>Jane</a>"
       "<a class='u-url' href='https://jane.example/notes/1'>#</a>"
       "<time class='dt-published' datetime='2026-10-11'>today</time>"
       "<div class='e-content'><p>Nice post!</p>"
       "<p>Second <a href='" target "'>para</a>.</p></div>"
       "<a class='" kind-class "' href='" target "'>re</a>"
       "<a class='u-in-reply-to' href='https://elsewhere.example/'>and that</a>"
       "</article>"))

(def pages
  "The sources, by URL."
  {"https://jane.example/reply"    (response "u-in-reply-to")
   "https://jane.example/like"     (response "u-like-of")
   "https://jane.example/unlinked" "<p>No link to anybody.</p>"})

(defn serve
  "Answer the `request` as the pages above would, and 410 Gone to any other."
  [{:keys [url] :as request}]
  (if-let [page (pages url)]
    {:status 200 :url url :headers {"content-type" "text/html"} :body page}
    {:status 410 :url url :headers {} :body ""}))

(defn verified
  "What verify-mention! keeps of the mention of our post by `source`, with
  the pages above in place of the network."
  [source]
  (let [dir  (str (Files/createTempDirectory "webmention" (make-array FileAttribute 0)))
        conf {:url "https://simon.grays.blog" :indieweb-dir dir}]
    (indieweb/ensure-dirs! dir)
    (with-redefs [http/send!      serve
                  http/get-image! (constantly nil)
                  ;; The pages' hosts resolve to no address, public or not.
                  wm/wm-opts      (assoc wm/wm-opts :allow-pred (constantly true))]
      (wm/verify-mention! conf source "/posts/2020/x")
      (get (indieweb/mentions dir "/posts/2020/x") source))))

(deftest verification
  (testing "a reply keeps an excerpt of its content, its paragraphs apart"
    (let [m (verified "https://jane.example/reply")]
      (is (= :verified (:status m)))
      (is (= :reply (:kind m)))
      (is (= "Nice post!\n\nSecond para." (:content m)))
      (is (= {:author-name "Jane"
              :author-url  "https://jane.example/"
              :url         "https://jane.example/notes/1"
              :published   "2026-10-11"}
             (select-keys m [:author-name :author-url :url :published])))))

  (testing "a like keeps no content, and our own post isn't among what it
            replies to"
    (let [m (verified "https://jane.example/like")]
      (is (= :like (:kind m)))
      (is (nil? (:content m)))
      (is (= #{"https://elsewhere.example/"} (:in-reply-to m)))))

  (testing "a source that no longer links us, or is gone, fails, which is how
            a verified mention is deleted"
    (is (= :failed (:status (verified "https://jane.example/unlinked"))))
    (is (= :failed (:status (verified "https://jane.example/gone"))))))
