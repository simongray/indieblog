(ns blog.grays.web.client
  "Client-side hydration of server-rendered pages.

  Currently a smoke test: adopts the frontpage articles from the EDN data
  embedded by `component/page` without attaching any behaviour. The hiccup
  passed to `hydrate` describes the *contents* of the container element,
  just like `render`. A clean hydration logs nothing; any Replicant mismatch
  warning in the console is a bug in either SSR/client parity or the
  hydration implementation."
  (:require [cljs.reader :as reader]
            [replicant.dom :as d]
            [blog.grays.web.component :as c]))

(defn ^:export init
  []
  (when-let [edn (.. js/document -body -dataset -hydrate)]
    (let [{:keys [posts conf]} (reader/read-string edn)]
      (d/hydrate (js/document.getElementById "main")
                 (c/frontpage-main posts conf)))))
