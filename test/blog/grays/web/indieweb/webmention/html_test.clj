(ns blog.grays.web.indieweb.webmention.html-test
  "Tests for reading foreign HTML: the rels and links that Web sign-in reads,
  and a page's title for its reply context.

  The microformats themselves are microformats-clj's, which has the spec's
  test suite, and what a mention shows is webmention-clj's."
  (:require [clojure.test :refer [deftest testing is]]
            [blog.grays.web.indieweb.webmention.html :as html]))

(defn parse
  "Parse `html` as though fetched from https://them.example/notes/1, so that
  relative URLs have something to resolve against."
  [html]
  (html/parse html "https://them.example/notes/1"))

(deftest title
  (is (= "Some Blog" (html/title (parse "<html><head><title> Some Blog </title></head>
                                         <body><p>hi</p></body></html>"))))
  (is (nil? (html/title (parse "<p>no title</p>")))))

(deftest links
  (testing "every link on the page, absolutised, wherever it is"
    (is (= #{"https://them.example/posts/2020/x"
             "https://us.example/absolute"
             "https://them.example/outside-the-entry"}
           (html/all-hrefs (parse "<div class='h-entry'>
                                    <a href='/posts/2020/x'>relative</a>
                                    <a href='https://us.example/absolute'>absolute</a>
                                  </div>
                                  <footer><a href='/outside-the-entry'>elsewhere</a></footer>")))))

  (testing "a page linking nowhere"
    (is (= #{} (html/all-hrefs (parse "<p>no links at all</p>"))))))

(deftest rels
  (let [href #(html/rel-href (parse %) "webmention")]
    (testing "a rel is found on <link> or <a>, the first in document order"
      (is (= "https://them.example/wm" (href "<link rel='webmention' href='/wm'>")))
      (is (= "https://them.example/wm" (href "<a rel='webmention' href='/wm'>wm</a>")))
      (is (= "https://them.example/first"
             (href "<link rel='webmention' href='/first'>
                    <a rel='webmention' href='/second'>wm</a>"))))

    (testing "rel may carry several values, and must match exactly"
      (is (= "https://them.example/wm"
             (href "<link rel='webmention somethingelse' href='/wm'>")))
      (is (nil? (href "<link rel='not-webmention' href='/wm'>")))
      (is (nil? (href "<link rel='webmentionx' href='/wm'>"))))

    (testing "an empty href means the page itself"
      (is (= "https://them.example/notes/1" (href "<link rel='webmention' href=''>"))))

    (testing "nothing advertised"
      (is (nil? (href "<p>nothing here</p>")))
      (is (nil? (href "<link rel='webmention'>"))
          "a <link> with no href advertises nothing"))))
