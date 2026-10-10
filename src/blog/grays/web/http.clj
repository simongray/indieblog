(ns blog.grays.web.http
  "The HTTP client we reach other sites with: Webmention discovery, delivery and
  verification, reply contexts, WebSub pings and the auth namespace's sign-in
  and OAuth flows.

  Every request goes through wary-fetch to the public internet only: each
  address a host resolves to is checked, redirects included, and each
  exchange has a time and a size limit. Responses are plain maps. The :url of
  a response is its *final* URL, redirects included, which is what relative
  hrefs in the body must be resolved against."
  (:require [clojure.string :as str]
            [dk.simongray.wary-fetch :as fetch]
            [dk.simongray.wary-fetch.headers :as fetch.headers]
            [dk.simongray.wary-fetch.url :as fetch.url])
  (:import [java.net URI]))

(def user-agent
  "Webmention (https://simon.grays.blog)")

(def limits
  "The limits of each exchange: ten seconds and five megabytes, more than any
  page or JSON reply we read needs."
  {:timeout-seconds 10
   :max-bytes       (* 5 1024 1024)})

(defn send!
  "Send the `request` map (see wary-fetch) with our User-Agent and limits, to
  the public internet only. A host that resolves to a private address, also
  after a redirect, throws instead."
  [request]
  (fetch/send-public! (merge {:user-agent user-agent} limits request)))

(defn- private-host?
  "Is `host` a loopback or private-range address? A cheap textual check to
  avoid fetching internal sources; not exhaustive."
  [host]
  (boolean
    (re-matches #"localhost|127\..+|10\..+|192\.168\..+|169\.254\..+|172\.(1[6-9]|2\d|3[01])\..+|\[?::1\]?"
                host)))

(defn valid-url?
  "Is `s` an absolute http(s) URL that names no obviously private host? A
  cheap check of the shape of a URL a stranger hands us, with no DNS lookup;
  send! checks every address a request actually goes to."
  [s]
  (boolean
    (when-let [uri (try (URI. (str s)) (catch Exception _ nil))]
      (and (#{"http" "https"} (.getScheme uri))
           (some? (.getHost uri))
           (not (private-host? (.getHost uri)))))))

(defn get!
  "GET `url`, optionally with extra `headers`."
  ([url]
   (get! url nil))
  ([url headers]
   (send! {:url url :headers headers})))

(def content-type->ext
  "The content types we cache an avatar from, each mapped to the extension
  we store it under — taken from here, never from the untrusted URL."
  {"image/jpeg" "jpg"
   "image/png"  "png"
   "image/gif"  "gif"
   "image/webp" "webp"})

(def max-image-bytes
  "The size cap on a cached avatar: a face needs no more, and it bounds what a
  stranger's u-photo can make us store."
  (* 2 1024 1024))

(defn get-image!
  "GET `url` as an image, {:bytes .. :ext ..} for a supported type, else nil.

  The extension is derived from the response content type, not the URL. A
  body past the size cap throws, so no more than the cap is downloaded."
  [url]
  (let [{:keys [status headers body]} (send! {:url       url
                                              :as        :bytes
                                              :max-bytes max-image-bytes})
        ext (-> (fetch.headers/header headers "content-type")
                (fetch.headers/media-type)
                (content-type->ext))]
    (when (and (< status 400) ext body)
      {:bytes body
       :ext   ext})))

(defn post-form!
  "POST `params` form-encoded to `url`, optionally with extra `headers`."
  ([url params]
   (post-form! url params nil))
  ([url params headers]
   (send! {:method  :post
           :url     url
           :headers (merge {"Content-Type" "application/x-www-form-urlencoded"}
                           headers)
           :body    (fetch.url/query-string params)})))

(defn ok?
  "Did the `response` come back without an error status?"
  [{:keys [status] :as response}]
  (< status 400))

(defn header-rel
  "The href of the first Link header in `response` carrying `rel`, as
  authored.

  A relative href must be resolved against the response :url by the caller."
  [{:keys [headers] :as response} rel]
  (some (fn [link]
          (when (contains? (set (str/split (str (:rel link)) #"\s+")) rel)
            (:href link)))
        (fetch.headers/links headers)))

(comment
  (:status (get! "https://simon.grays.blog"))
  (:url (get! "https://webmention.rocks/test/23/page"))      ; a redirect
  (get! "http://localtest.me/")                              ; refused: 127.0.0.1
  #_.)
