(ns blog.grays.web.feed-test
  "Tests for the RSS feed.

  Only the URL rewrite is covered. A mistake here shows up as a broken image
  in someone else's reader, where we never see it. See feed/absolutize."
  (:require [clojure.test :refer [deftest testing is]]
            [blog.grays.web.content :as content]
            [blog.grays.web.feed :as feed]))

(def conf
  {:url      "https://simon.grays.blog"
   :name     "Simon Gray"
   :tagline  "A blog"
   :language "en"
   :email    "simon@example.com"
   :author   "Simon Gray"})

(def link
  "https://simon.grays.blog/posts/2026/a-post")

(defn urls
  "Every src and href URL in the feed XML for `markdown` rendered as a post."
  [markdown]
  (let [post (content/md->post (str "---\ndate: 2026-08-08\n---\n" markdown)
                               "/tmp/a-post.md")]
    (->> (feed/xml conf [(assoc post :year "2026" :slug "a-post")])
         (re-seq #"(?:src|href)=\"([^\"]+)\"")
         (map second)
         (set))))

(deftest absolutize-url
  (let [f (partial feed/absolutize-url (:url conf) link)]
    (testing "a site-root path is qualified against the site"
      (is (= "https://simon.grays.blog/assets/cat.jpg" (f "/assets/cat.jpg")))
      (is (= "https://simon.grays.blog/about" (f "/about")))
      (is (= "https://simon.grays.blog/" (f "/"))))

    (testing "a bare fragment is qualified against the item, not the feed"
      (is (= (str link "#section") (f "#section"))))

    (testing "what is already absolute enough is left alone"
      (is (= "https://example.com/y" (f "https://example.com/y")))
      (is (= "mailto:simon@example.com" (f "mailto:simon@example.com")))
      ;; Prefixing a protocol-relative URL would only corrupt it.
      (is (= "//cdn.example.com/x.png" (f "//cdn.example.com/x.png"))))))

(deftest no-relative-urls-in-feed-items
  (testing "an asset embedded in a post is fully qualified in the feed"
    (is (contains? (urls "![cat](assets/cat.jpg)")
                   "https://simon.grays.blog/assets/cat.jpg")))

  (testing "an internal link is fully qualified too"
    (is (contains? (urls "[about](/about)")
                   "https://simon.grays.blog/about")))

  (testing "nothing relative escapes, whatever the post contains"
    (let [found (urls (str "![cat](assets/cat.jpg) [about](/about) "
                           "[frag](#section) [ext](https://example.com/y)"))]
      (is (empty? (remove #(re-find #"^(https?:)?//|^mailto:" %) found))))))
