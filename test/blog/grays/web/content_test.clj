(ns blog.grays.web.content-test
  "Tests for turning markdown into post entities.

  Two things are covered, since nothing fails when either is wrong. The asset
  rewrite: the image just does not appear (see content/absolutize-asset). And
  the plain text of a post: words just go missing from search and
  descriptions (see shared/stringify)."
  (:require [clojure.test :refer [deftest testing is]]
            [blog.grays.web.content :as content]
            [blog.grays.web.shared :as shared]))

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

(defn post-text
  "The Hiccup of `markdown` parsed as a post, as plain text."
  [markdown]
  (shared/stringify (:hiccup (content/md->post markdown "/tmp/a-post.md"))))

(deftest text-of-parsed-posts
  (testing "the items of a tight list keep their text"
    ;; Markdown puts the text of a tight list item in a seq, not a vector.
    (is (= "Steps: one two" (post-text "Steps:\n\n- one\n- two"))))

  (testing "the items of a loose list, too"
    (is (= "one two" (post-text "- one\n\n- two"))))

  (testing "block elements are kept apart, inline ones are not"
    (is (= "A bold c. Next" (post-text "A **bold** c.\n\nNext")))))
