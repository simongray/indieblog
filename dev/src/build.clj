(ns build
  "A basic build script for creating an uberjar."
  (:require [org.corfield.build :as bb]))

(def lib 'blog.grays/web)
(def main 'blog.grays.web.service)

(defn ci
      "Run the CI pipeline of tests (and build the uberjar)."
      [opts]
      (-> opts
          (assoc :lib lib :main main)
          (bb/clean)
          (bb/uber)))

(comment
  (ci {:uber-file "blog.jar"})
  #_.)