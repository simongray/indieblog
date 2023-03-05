(ns blog.grays.web.interceptors
  (:require [io.pedestal.interceptor :as ic]
            [blog.grays.web.component :as c]
            [blog.grays.web.db :as db]))

(defn add-conf
  "Attaches `conf` to the request; should precede handlers."
  [conf]
  (ic/interceptor
    {:name  ::attach-conf
     :enter (fn [ctx] (assoc-in ctx [:request :conf] conf))}))

(defn frontpage
  [{:keys [conf] :as req}]
  (let [{:keys [name db-dir]} conf
        conn     (db/pconn db-dir)
        articles (db/latest-articles conn)]
    {:status  200
     :headers {"Content-Type" "text/html"}
     :body    (c/html-page
                [:main (c/posts (concat articles articles articles))]
                (assoc conf
                  :title name))}))

(defn single-post
  [{:keys [conf path-params] :as req}]
  (let [{:keys [name db-dir]} conf
        {:keys [year slug]} path-params
        conn    (db/pconn db-dir)
        article (db/single-article conn year slug)]
    {:status  200
     :headers {"Content-Type" "text/html"}
     :body    (c/html-page
                [:main (c/post article (rand-nth c/theme))]
                (assoc conf
                  :title (str (:title article) " — " name)
                  :page-type :article)
                true)}))

