(ns blog.grays.web.indieweb.micropub-test
  "Tests for the mapping between Micropub entries and post files, the part
  of our Micropub endpoint that micropub-clj leaves to us. An update reads a
  post as an entry and writes it back, so the round trip must lose nothing.
  See the micropub namespace."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest testing is]]
            [blog.grays.web.indieweb.micropub :as micropub])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def post
  "The frontmatter and body of a post file, with an attribute that no
  property maps to."
  [{:date        "2026-10-12"
    :title       "Hello"
    :slug        "hello"
    :tags        "indieweb, clojure"
    :reply-to    "https://example.com/a"
    :syndication "https://a.example/1 https://b.example/2"
    :location    "Copenhagen"
    :language    "da"}
   "Hi there"])

(deftest round-trips
  (testing "a post reads as an entry"
    (is (= {:type       ["h-entry"]
            :properties {:content     ["Hi there"]
                         :name        ["Hello"]
                         :published   ["2026-10-12"]
                         :category    ["indieweb" "clojure"]
                         :in-reply-to ["https://example.com/a"]
                         :syndication ["https://a.example/1" "https://b.example/2"]
                         :location    ["Copenhagen"]}}
           (micropub/post->entry post))))

  (testing "and writes back as it was, the attributes it doesn't map included"
    (is (= post (micropub/entry->post (first post) (micropub/post->entry post)))))

  (testing "a property left out of the entry leaves the frontmatter"
    (is (= {:date "2026-10-12" :slug "hello" :language "da"}
           (first (micropub/entry->post (first post)
                                        {:properties {:published ["2026-10-12"]}}))))))

(deftest creates
  (testing "photos go into the body, as Markdown images with their alt text"
    (is (= "Lunch\n\n![A sunset](https://x.example/sunset.jpg)\n\n![](https://x.example/city.jpg)"
           (second (micropub/entry->post
                    {}
                    {:properties {:content ["Lunch"]
                                  :photo   [{:value "https://x.example/sunset.jpg"
                                             :alt   "A sunset"}
                                            "https://x.example/city.jpg"]}})))))

  (testing "a published date and time is written as its date, an rsvp in lower case"
    (is (= {:date "2017-05-31" :rsvp "yes"}
           (first (micropub/entry->post {} {:properties {:published ["2017-05-31T12:03:36-07:00"]
                                                         :rsvp      ["YES"]}})))))

  (testing "a photo note is named after its words, not its photo"
    (let [dir  (str (Files/createTempDirectory "micropub-test" (make-array FileAttribute 0)))
          conf {:posts-dir dir :url "https://simon.grays.blog"}
          db   (atom {:posts {}})
          url  #(:url (micropub/create! db conf {:type ["h-entry"] :properties %}))]
      (is (re-find #"/posts/\d{4}/a-sunset$"
                   (url {:content ["A sunset"]
                         :photo   ["https://simon.grays.blog/assets/2026-10-13-dusk.jpg"]})))
      (testing "or after its file when it has none"
        (is (re-find #"/posts/\d{4}/2026-10-13-cat$"
                     (url {:photo [{:value "https://simon.grays.blog/assets/2026-10-13-cat.png"
                                    :alt   "A cat"}]}))))
      (run! io/delete-file (reverse (file-seq (io/file dir))))))

  (testing "HTML content becomes Markdown, and a nested object is ignored (3.3.5)"
    (is (= [{} "**Hi**"]
           (micropub/entry->post {} {:properties {:content  [{:html "<b>Hi</b>"}]
                                                  :location [{:type       ["h-card"]
                                                              :properties {:name ["Café"]}}]}})))))
