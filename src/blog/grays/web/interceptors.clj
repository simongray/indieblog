(ns blog.grays.web.interceptors
  (:require [io.pedestal.interceptor :as ic]
            [blog.grays.web.feed :as feed]
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
        conn   (db/pconn db-dir)
        latest (db/latest-posts conn)]
    {:status  200
     :headers {"Content-Type" "text/html"}
     :body    (c/html-page
                [:main (c/article-elems latest)]
                (assoc conf
                  :title name))}))

(defn single-post
  [{:keys [conf path-params] :as req}]
  (let [{:keys [name db-dir]} conf
        {:keys [year slug]} path-params
        conn   (db/pconn db-dir)
        single (db/single-post conn year slug)]
    {:status  200
     :headers {"Content-Type" "text/html"}
     :body    (c/html-page
                [:main (c/article-elem single (rand-nth c/theme))]
                (assoc conf
                  :title (str (:title single) " — " name)
                  :page-type :post)
                true)}))

(defn atom-feed
  [{:keys [conf] :as req}]
  (let [{:keys [db-dir]} conf
        conn  (db/pconn db-dir)
        posts (take 10 (db/latest-posts conn))]
    {:status  200
     :headers {"Content-Type" "application/atom+xml"}
     :body    (feed/xml conf posts)}))
