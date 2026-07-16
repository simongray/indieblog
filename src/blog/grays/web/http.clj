(ns blog.grays.web.http
  "The HTTP client we reach other sites with: Webmention discovery, delivery and
  verification, reply contexts, WebSub pings and IndieAuth token verification.

  Responses are returned as plain maps, so java.net.http stays in here. The
  :url of a response is its *final* URL, redirects included — what relative
  hrefs in the body must be resolved against."
  (:require [clojure.string :as str])
  (:import [java.net URI URLEncoder]
           [java.net.http HttpClient HttpClient$Redirect HttpRequest
                          HttpRequest$BodyPublishers HttpResponse
                          HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.time Duration]))

(def user-agent
  "Webmention (https://simon.grays.blog)")

(defonce ^:private client
  (delay (-> (HttpClient/newBuilder)
             (.followRedirects HttpClient$Redirect/NORMAL)
             (.connectTimeout (Duration/ofSeconds 10))
             (.build))))

(defn- request
  "A partially built HTTP request for `url` with `headers`, a timeout and our
  User-Agent set."
  [url headers]
  (reduce-kv (fn [builder k v]
               (.header builder k v))
             (-> (HttpRequest/newBuilder (URI. url))
                 (.timeout (Duration/ofSeconds 10))
                 (.header "User-Agent" user-agent))
             headers))

(defn- response->map
  [^HttpResponse response]
  {:status  (.statusCode response)
   :url     (str (.uri response))
   :headers (into {} (.map (.headers response)))
   :body    (.body response)})

(defn- send!
  [builder]
  (response->map (.send @client
                        (.build builder)
                        (HttpResponse$BodyHandlers/ofString))))

(defn GET
  "GET `url`, optionally with extra `headers`."
  ([url]
   (GET url nil))
  ([url headers]
   (send! (.GET (request url headers)))))

(def ^:private image-types
  "The content types we cache an avatar from, each mapped to the extension we
  store it under. The extension comes from here, never from the untrusted URL."
  {"image/jpeg" "jpg"
   "image/png"  "png"
   "image/gif"  "gif"
   "image/webp" "webp"})

(def ^:private max-image-bytes
  "The size cap on a cached avatar: a face needs no more, and it bounds what a
  stranger's u-photo can make us store."
  (* 2 1024 1024))

(defn GET-image
  "GET `url` as an image, {:bytes .. :ext ..} for a supported type within the
  size cap, else nil.

  The extension is derived from the response content type, not the URL. The cap
  is a backstop applied after the body is read: java.net.http buffers it whole,
  so this bounds what we store, not what we download."
  [url]
  (let [response (.send @client
                        (.build (.GET (request url nil)))
                        (HttpResponse$BodyHandlers/ofByteArray))
        ctype    (some-> ^HttpResponse response
                         (.headers)
                         (.firstValue "content-type")
                         (.orElse nil)
                         (str/split #";")
                         (first)
                         (str/trim)
                         (str/lower-case))
        bytes    (.body response)]
    (when (and (< (.statusCode response) 400)
               (contains? image-types ctype)
               (<= (alength ^bytes bytes) max-image-bytes))
      {:bytes bytes
       :ext   (image-types ctype)})))

(defn- form-encode
  [m]
  (->> (for [[k v] m]
         (str (name k) "=" (URLEncoder/encode (str v) StandardCharsets/UTF_8)))
       (str/join "&")))

(defn POST-form
  "POST `params` form-encoded to `url`."
  [url params]
  (send! (-> (request url {"Content-Type" "application/x-www-form-urlencoded"})
             (.POST (HttpRequest$BodyPublishers/ofString (form-encode params))))))

(defn ok?
  "Did the `response` come back without an error status?"
  [{:keys [status] :as response}]
  (< status 400))

(comment
  (:status (GET "https://simon.grays.blog"))
  (:url (GET "https://webmention.rocks/test/23/page"))       ; a redirect
  #_.)
