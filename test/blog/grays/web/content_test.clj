(ns blog.grays.web.content-test
  "Tests for turning markdown into post entities.

  Only the asset rewrite is covered. Nothing fails when it is wrong, the image
  just does not appear, so the mapping is pinned here rather than left to be
  noticed. See content/absolutize-asset."
  (:require [clojure.test :refer [deftest testing is]]
            [blog.grays.web.content :as content]))

(defn attr-urls
  "Every `k` attribute in the Hiccup of `markdown` parsed as a post."
  [k markdown]
  (->> (:hiccup (content/md->post markdown "/tmp/a-post.md"))
       (tree-seq vector? seq)
       (keep #(when (map? %) (k %)))))

(deftest absolutize-asset
  (testing "a relative asset path becomes site-absolute"
    (is (= "/assets/cat.jpg" (content/absolutize-asset "assets/cat.jpg")))
    (is (= "/assets/cat.jpg" (content/absolutize-asset "./assets/cat.jpg")))
    (is (= "/assets/2026/cat.jpg"
           (content/absolutize-asset "assets/2026/cat.jpg"))))

  (testing "an already-absolute URL is left alone, so this is idempotent"
    (is (= "/assets/cat.jpg" (content/absolutize-asset "/assets/cat.jpg")))
    ;; What the Micropub media endpoint hands its clients.
    (is (= "https://simon.grays.blog/assets/cat.jpg"
           (content/absolutize-asset
             "https://simon.grays.blog/assets/cat.jpg"))))

  (testing "a relative path outside the assets dir is not ours to rewrite"
    ;; A link to a sibling post resolves in an editor too, but maps to
    ;; /posts/<year>/<slug>, which a single file cannot know.
    (is (= "other-post.md" (content/absolutize-asset "other-post.md")))
    (is (= "assets.md" (content/absolutize-asset "assets.md")))
    (is (= "my-assets/cat.jpg"
           (content/absolutize-asset "my-assets/cat.jpg")))))

(deftest asset-urls-in-parsed-posts
  (testing "the rewrite is wired into parsing"
    (is (= ["/assets/cat.jpg" "/assets/dog.jpg"]
           (attr-urls :src "![cat](assets/cat.jpg) ![dog](./assets/dog.jpg)"))))

  (testing "links to non-image assets get the same treatment"
    (is (= ["/assets/paper.pdf"]
           (attr-urls :href "[paper](assets/paper.pdf)"))))

  (testing "the markdown is stored as authored, for Micropub source queries"
    (is (= "![cat](assets/cat.jpg)"
           (:content (content/md->post "![cat](assets/cat.jpg)"
                                       "/tmp/a-post.md"))))))
