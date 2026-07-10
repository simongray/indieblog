(ns blog.grays.web.interceptors
  "Pedestal interceptors and handlers backing the web service routes."
  (:require [io.pedestal.interceptor :as ic]
            [taoensso.telemere :as tel]
            [blog.grays.web.feed :as feed]
            [blog.grays.web.component :as c]
            [blog.grays.web.db :as db]))

(defn attach-conf
  "Attaches `conf` and the content db connection to the request; should
  precede handlers."
  [{:keys [db-dir] :as conf}]
  (let [conn (db/get-conn db-dir)]
    (ic/interceptor
      {:name  ::attach-conf
       :enter (fn [ctx] (update ctx :request assoc :conf conf :conn conn))})))

(defn html-response
  ([body]
   (html-response 200 body))
  ([status body]
   {:status  status
    :headers {"Content-Type" "text/html"}
    :body    body}))

(defn frontpage
  [{:keys [conf conn] :as req}]
  (html-response
    (c/page (:name conf)
            [:main (c/articles (db/get-posts conn))]
            conf)))

(defn single-post
  [{:keys [conf conn path-params] :as req}]
  (let [{:keys [name]} conf
        {:keys [year slug]} path-params]
    (if-let [post (db/get-post conn year slug)]
      (html-response
        (c/page (str (:title post) " — " name)
                [:main (c/article post (rand-nth c/palette))]
                conf
                :reader? true))
      (do
        (tel/log! {:level :warn
                   :id    ::post-not-found
                   :data  {:year year :slug slug}
                   :msg   (str "No post found for " year "/" slug)})
        (html-response 404 (c/page (str "Not found — " name)
                                   (c/not-found year slug)
                                   conf))))))

(defn atom-feed
  [{:keys [conf conn] :as req}]
  {:status  200
   :headers {"Content-Type" "application/atom+xml"}
   :body    (feed/xml conf (take 10 (db/get-posts conn)))})
