(ns blog.grays.web.interceptors
  "Pedestal interceptors to create resources for the web service."
  (:require [io.pedestal.interceptor :as ic]
            [taoensso.telemere :as tel]
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
    (if-not single
      (do
        (tel/log! {:level :warn
                   :id    ::post-not-found
                   :data  {:year year :slug slug}
                   :msg   (str "No post found for " year "/" slug)})
        {:status  404
         :headers {"Content-Type" "text/html"}
         :body    (c/html-page
                    [:main
                     [:article
                      [:h1 "Not found"]
                      [:p "No such post: " [:strong year "/" slug]]
                      [:p [:a.post-link {:href "/"} "↩ to main page"]]]]
                    (assoc conf
                      :title (str "Not found — " name)))})
      {:status  200
       :headers {"Content-Type" "text/html"}
       :body    (c/html-page
                  [:main (c/article-elem single (rand-nth c/theme))]
                  (assoc conf
                    :title (str (:title single) " — " name))
                  true)})))

(defn atom-feed
  [{:keys [conf] :as req}]
  (let [{:keys [db-dir]} conf
        conn  (db/pconn db-dir)
        posts (take 10 (db/latest-posts conn))]
    {:status  200
     :headers {"Content-Type" "application/atom+xml"}
     :body    (feed/xml conf posts)}))
