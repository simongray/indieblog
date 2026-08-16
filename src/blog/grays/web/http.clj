(ns blog.grays.web.http
  "The HTTP client we reach other sites with: Webmention discovery, delivery and
  verification, reply contexts, WebSub pings and IndieAuth token verification.

  Responses are returned as plain maps, so java.net.http stays in here. The
  :url of a response is its *final* URL, redirects included, which is what
  relative hrefs in the body must be resolved against."
  (:require [clojure.string :as str])
  (:import [java.net URI URLEncoder]
           [java.net.http HttpClient HttpClient$Redirect HttpRequest
                          HttpRequest$BodyPublishers HttpResponse
                          HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.time Duration]))

(def user-agent
  "Webmention (https://simon.grays.blog)")

(defonce client
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

(defn send!
  "Build and send the request from `builder`, reading the body as a string
  unless a `handler` is given."
  ([builder]
   (send! builder (HttpResponse$BodyHandlers/ofString)))
  ([builder handler]
   (response->map (.send @client (.build builder) handler))))

(defn- private-host?
  "Is `host` a loopback or private-range address? A cheap textual check to
  avoid fetching internal sources; not exhaustive."
  [host]
  (boolean
    (re-matches #"localhost|127\..+|10\..+|192\.168\..+|169\.254\..+|172\.(1[6-9]|2\d|3[01])\..+|\[?::1\]?"
                host)))

(defn valid-url?
  "Is `s` an absolute, public http(s) URL? The guard on every URL a stranger
  can make us fetch."
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
   (send! (.GET (request url headers)))))

(def content-type->ext
  "The content types we cache an avatar from, each mapped to the extension we
  store it under; the sibling of micropub/content-type->ext, whose accepted
  types deliberately differ. The extension comes from here, never from the
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
  "GET `url` as an image, {:bytes .. :ext ..} for a supported type within the
  size cap, else nil.

  The extension is derived from the response content type, not the URL. The cap
  is a backstop applied after the body is read: java.net.http buffers it whole,
  so this bounds what we store, not what we download."
  [url]
  (let [{:keys [status headers body]} (send! (.GET (request url nil))
                                             (HttpResponse$BodyHandlers/ofByteArray))
        ctype (some-> (first (get headers "content-type"))
                      (str/split #";")
                      (first)
                      (str/trim)
                      (str/lower-case))]
    (when (and (< status 400)
               (contains? content-type->ext ctype)
               (<= (alength ^bytes body) max-image-bytes))
      {:bytes body
       :ext   (content-type->ext ctype)})))

(defn- url-encode
  "URL-encode `s` for use in a query string or form body."
  [s]
  (URLEncoder/encode (str s) StandardCharsets/UTF_8))

(defn form-encode
  "The map `m` as an application/x-www-form-urlencoded string, for a form body
  or a query string; the two share the encoding."
  [m]
  (->> (for [[k v] m]
         (str (name k) "=" (url-encode v)))
       (str/join "&")))

(defn post-form!
  "POST `params` form-encoded to `url`, optionally with extra `headers`."
  ([url params]
   (post-form! url params nil))
  ([url params headers]
   (send! (-> (request url (merge {"Content-Type" "application/x-www-form-urlencoded"}
                                  headers))
              (.POST (HttpRequest$BodyPublishers/ofString (form-encode params)))))))

(defn ok?
  "Did the `response` come back without an error status?"
  [{:keys [status] :as response}]
  (< status 400))

(comment
  (:status (get! "https://simon.grays.blog"))
  (:url (get! "https://webmention.rocks/test/23/page"))      ; a redirect
  #_.)
