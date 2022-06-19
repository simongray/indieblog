(ns blog.grays.web.interceptors
  (:require [io.pedestal.interceptor :as ic]
            [blog.grays.web.component :as c]))

(defn add-conf
  "Attaches `conf` to the request; should precede handlers."
  [conf]
  (ic/interceptor {:name ::attach-conf
                   :enter (fn [ctx] (assoc-in ctx [:request :conf] conf))}))

(defn root
  [{:keys [conf] :as req}]
  (let [{:keys [title]} conf]
    {:status  200
     :headers {"Content-Type" "text/html"}
     :body    (c/html-page title [:p "hello world"] conf)}))
