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
