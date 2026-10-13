(ns blog.grays.web.http
  "The HTTP client we reach other sites with, the libraries included.

  Every request goes through wary-fetch to the public internet only: each
  address a host resolves to is checked, redirects included, and each
  exchange has a time and a size limit. Responses are plain maps. The :url of
  a response is its *final* URL, redirects included, which is what relative
  hrefs in the body must be resolved against."
  (:require [taoensso.telemere :as tel]
            [dk.simongray.wary-fetch :as fetch]
            [dk.simongray.whatwg-mime :as mime]))

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

(def opts
  "The options that make a library's requests ours: sent with send!, and
  with our User-Agent rather than the library's."
  {:send       #'send!
   :user-agent user-agent})

(defn page!
  "GET the page at `url`: the response when it answers with a success, else
  nil, logged."
  [url]
  (try
    (let [{:keys [status] :as response} (send! {:url url})]
      (when (< status 400)
        response))
    (catch Exception e
      (tel/log! {:level :info
                 :id    ::unreachable
                 :data  {:url url}
                 :msg   (str "Could not fetch " url ": " (ex-message e))})
      nil)))

(def content-type->ext
  "The content types we copy an image from, e.g. an avatar, each mapped to
  the extension we store it under — taken from here, never from the
  untrusted URL."
  {"image/jpeg" "jpg"
   "image/png"  "png"
   "image/gif"  "gif"
   "image/webp" "webp"})

(def max-image-bytes
  "The size cap on a cached avatar: a face needs no more, and it bounds what a
  stranger's u-photo can make us store."
  (* 2 1024 1024))

(defn get-image!
  "GET `url` as an image of at most `max-bytes` (max-image-bytes unless
  given), {:bytes .. :ext ..} for a supported type, else nil.

  The extension is derived from the response content type, not the URL. A
  body past the size cap throws, so no more than the cap is downloaded."
  ([url]
   (get-image! url max-image-bytes))
  ([url max-bytes]
   (let [{:keys [status headers body]} (send! {:url       url
                                               :as        :bytes
                                               :max-bytes max-bytes})
         ext (-> (mime/extract (get headers "content-type"))
                 (mime/essence)
                 (content-type->ext))]
     (when (and (< status 400) ext body)
       {:bytes body
        :ext   ext}))))

(comment
  (:status (page! "https://simon.grays.blog"))
  (:url (page! "https://webmention.rocks/test/23/page"))     ; a redirect
  (page! "http://localtest.me/")                             ; refused: 127.0.0.1
  #_.)
