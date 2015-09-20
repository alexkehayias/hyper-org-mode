(ns hyper-org-mode.middleware
  (:require [ring.middleware.params :refer :all]
            [clojure.walk :refer [keywordize-keys]]
            [cheshire.core :as json]
            [taoensso.timbre :as log]
            [clojure.string :as s]))


(defn input-stream->str
  "Coerce an http-kit InputStream into a string"
  [s]
  (slurp (.bytes ^org.httpkit.BytesInputStream s)))

(defn parse-json-body
  "If there is body, parse the body as json"
  [f]
  (fn [req]
    (if (:body req)
      (f (assoc req :body (json/parse-string (input-stream->str (:body req)) true)))
      (f req))))

(defn log-request
  [f]
  (fn [req]
    (log/info "Request received"  (:uri req) (:body req))
    (f req)))

(defn append-conf
  "Append the value of conf to the :conf key of a request"
  [f conf]
  (fn [req]
    (f (assoc req :conf conf))))

(defn keyword-wrap-params
  "Copied from ring.middleware.params to keywordize param keys

   Middleware to parse urlencoded parameters from the query string and form
   body (if the request is a urlencoded form). Adds the following keys to
   the request map:
     :query-params - a map of parameters from the query string
     :form-params  - a map of parameters from the body
     :params       - a merged map of all types of parameter
   Takes an optional configuration map. Recognized keys are:
     :encoding - encoding to use for url-decoding. If not specified, uses
                 the request character encoding, or \"UTF-8\" if no request
                 character encoding is set."
  [handler & [opts]]
  (fn [request]
    (-> request
        (params-request opts)
        keywordize-keys
        handler)))
