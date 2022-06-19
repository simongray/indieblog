(ns blog.grays.web.hiccup
  "Broadly compatible Hiccup-generating functions.")

(defn rel=me
  "Get a single rel=me :link/:a based on a `tag` and an `identity-kv`."
  [tag [href {:keys [label]} :as identity-kv]]
  [tag {:rel "me" :href href :title label}])

(defn rel=me-links
  "Get rel=me links to be used in the HTML head based on href+title `identity`."
  [identity]
  (map (partial rel=me :link) identity))

(defn html-head
  [title {:keys [identity] :as conf}]
  (into [:head
         [:meta {:charset "UTF-8"}]
         [:meta {:name    "viewport"
                 :content "width=device-width, initial-scale=1.0"}]]
        (remove nil? [(when title
                        [:title title])
                      (when identity
                        (rel=me-links identity))])))
