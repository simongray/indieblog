(ns blog.grays.web.indieweb.webmention.html-test
  "Tests for reading foreign HTML.

  The mf2 subset we implement is an approximation (see the html namespace) and
  the approximation has bitten us twice, both times because a plain CSS
  selector reads microformats wrongly. Those two are the regressions worth
  guarding, so they lead. The rest pins down the shape of what `entry` hands
  back, since everything downstream now trusts it as data."
  (:require [clojure.test :refer [deftest testing is]]
            [blog.grays.web.indieweb.webmention.html :as html]))

(defn entry
  "Parse `html` as though fetched from https://them.example/notes/1, so that
  relative URLs have something to resolve against."
  [html]
  (html/entry (html/parse html "https://them.example/notes/1")))

(deftest nested-microformat-roots
  (testing "a property inside a nested root belongs to that root, not to ours"
    (testing "an author h-card's p-name is not the title of the post"
      (is (= "The Real Post Title"
             (:title (entry "<article class='h-entry'>
                               <a class='p-author h-card' href='/me'>
                                 <span class='p-name'>Jane Doe</span></a>
                               <h1 class='p-name'>The Real Post Title</h1>
                             </article>")))))

    (testing "an author's p-org h-card does not shadow the author's own name"
      (is (= "Jane Doe"
             (get-in (entry "<article class='h-entry'>
                              <a class='p-author h-card' href='/me'>
                                <span class='p-name'>Jane Doe</span>
                                <span class='p-org h-card'>
                                  <span class='p-name'>Acme Corp</span></span></a>
                            </article>")
                     [:author :name]))))

    (testing "an embedded h-cite does not supply the entry's own properties"
      (let [e (entry "<article class='h-entry'>
                        <h1 class='p-name'>Ours</h1>
                        <time class='dt-published' datetime='2026-07-14'>today</time>
                        <blockquote class='h-cite'>
                          <span class='p-name'>Theirs</span>
                          <time class='dt-published' datetime='1999-01-01'>then</time>
                        </blockquote>
                      </article>")]
        (is (= "Ours" (:title e)))
        (is (= "2026-07-14" (:published e)))))))

(deftest value-attribute-forms
  (testing "properties whose value lives in an attribute, having no text"
    (is (= "Slept from 10:45pm to 5:57am"
           (:title (entry "<div class='h-entry'>
                            <data class='p-name' value='Slept from 10:45pm to 5:57am'></data>
                          </div>"))))
    (is (= "Full Title"
           (:title (entry "<div class='h-entry'>
                            <abbr class='p-name' title='Full Title'>FT</abbr></div>"))))
    (is (= "Alt Title"
           (:title (entry "<div class='h-entry'>
                            <img class='p-name' src='/t.png' alt='Alt Title'></div>"))))
    (is (= "Plain Text Title"
           (:title (entry "<div class='h-entry'>
                            <h1 class='p-name'>Plain Text Title</h1></div>")))
        "the ordinary case still reads element text")))

(deftest title
  (testing "the <title> element is the fallback"
    (is (= "Some Blog"
           (:title (entry "<html><head><title>Some Blog</title></head>
                           <body><p>no microformats here</p></body></html>"))))
    (is (= "Some Blog"
           (:title (entry "<html><head><title>Some Blog</title></head>
                           <body><div class='h-entry'>an h-entry with no p-name</div>
                           </body></html>"))))
    (is (nil? (:title (entry "<p>neither a title nor an h-entry</p>"))))))

(deftest author
  (testing "the common <a class='p-author h-card'>, its name implied by its text"
    (is (= {:name "Jane Doe" :url "https://them.example/me"}
           (:author (entry "<div class='h-entry'>
                             <a class='p-author h-card' href='/me'>Jane Doe</a></div>")))))

  (testing "nested p-name/u-url/u-photo properties, all URLs absolutised"
    (is (= {:name  "Jane Doe"
            :url   "https://them.example/me"
            :photo "https://them.example/jane.jpg"}
           (:author (entry "<div class='h-entry'>
                             <div class='p-author h-card'>
                               <span class='p-name'>Jane Doe</span>
                               <a class='u-url' href='/me'>home</a>
                               <img class='u-photo' src='/jane.jpg' alt=''>
                             </div></div>")))))

  (testing "no p-author marked up at all"
    (is (nil? (:author (entry "<div class='h-entry'>
                                <h1 class='p-name'>Untitled by nobody</h1></div>"))))
    (is (nil? (:author (entry "<p>not even an h-entry</p>"))))))

(deftest published
  (is (= "2026-07-14"
         (:published (entry "<div class='h-entry'>
                              <time class='dt-published' datetime='2026-07-14'>today</time>
                            </div>"))))
  (is (nil? (:published (entry "<div class='h-entry'>no date</div>")))))

(deftest links
  (testing "every link on the page, absolutised; a Webmention source need only
            link its target somewhere, not from within the h-entry"
    (is (= #{"https://them.example/posts/2020/x"
             "https://us.example/absolute"
             "https://them.example/outside-the-entry"}
           (:links (entry "<div class='h-entry'>
                            <a href='/posts/2020/x'>relative</a>
                            <a href='https://us.example/absolute'>absolute</a>
                          </div>
                          <footer><a href='/outside-the-entry'>elsewhere</a></footer>")))))

  (testing "a page linking nowhere"
    (is (= #{} (:links (entry "<p>no links at all</p>"))))))

(deftest kinds
  (testing "the kind of mention a source makes of us, absolutised"
    (let [e (entry "<div class='h-entry'>
                     <a class='u-in-reply-to' href='/posts/2020/x'>re</a>
                     <a class='u-like-of' href='/posts/2020/y'>♥</a>
                     <a class='u-repost-of' href='https://us.example/z'>RT</a>
                   </div>")]
      (is (= #{"https://them.example/posts/2020/x"} (:reply e)))
      (is (= #{"https://them.example/posts/2020/y"} (:like e)))
      (is (= #{"https://us.example/z"} (:repost e)))))

  (testing "a plain mention is marked up as no kind at all"
    (let [e (entry "<div class='h-entry'><a href='/posts/2020/x'>as seen here</a></div>")]
      (is (= #{} (:reply e) (:like e) (:repost e)))
      (is (contains? (:links e) "https://them.example/posts/2020/x")))))

(deftest endpoint-href
  (let [href #(html/endpoint-href (html/parse % "https://them.example/notes/1"))]
    (testing "the endpoint is advertised by <link> or <a>, in either order"
      (is (= "/wm" (href "<link rel='webmention' href='/wm'>")))
      (is (= "/wm" (href "<a rel='webmention' href='/wm'>wm</a>")))
      (is (= "/first" (href "<link rel='webmention' href='/first'>
                             <a rel='webmention' href='/second'>wm</a>"))))

    (testing "rel may carry several values, and must match exactly"
      (is (= "/wm" (href "<link rel='webmention somethingelse' href='/wm'>")))
      (is (nil? (href "<link rel='not-webmention' href='/wm'>")))
      (is (nil? (href "<link rel='webmentionx' href='/wm'>"))))

    (testing "an empty href means the page itself; the caller resolves it"
      (is (= "" (href "<link rel='webmention' href=''>"))))

    (testing "no endpoint advertised"
      (is (nil? (href "<p>nothing here</p>")))
      (is (nil? (href "<link rel='webmention'>"))
          "a <link> with no href advertises nothing"))))
