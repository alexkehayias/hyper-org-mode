(ns hyper-org-mode.server
  (:require [compojure.core :refer [defroutes GET POST]]
            [compojure.route :refer [not-found]]
            [org.httpkit.server :refer [run-server]]
            [clj-jgit.porcelain :refer :all]
            [hyper-org-mode.settings :as settings]
            [hyper-org-mode.middleware :refer [parse-json-body
                                               input-stream->str
                                               log-request
                                               append-conf
                                               keyword-wrap-params]]))


(def not-found-response
  {:status_code 404
   :body "{\"message\": \"Not found\", \"status_code\": 404}"})

(def ok-response
  {:status_code 404
   :body "{\"message\": \"ok\", \"status_code\": 200}"})

(defn push-file [{:keys [params body conf]}]
  "Example:
  curl -X post -d @./todo.org 127.0.0.1:1986/api/v1/push/todo.org"
  ;; TODO merge the file into the master version of the file
  ;; if the merge fails return a fail response
  ok-response)

(defn pull-file [{:keys [params conf]}]
  (let [file (clojure.java.io/file (:file-path params))]
    (if (.exists file)
      {:body file}
      not-found-response)))

(defonce server (atom nil))

(defroutes routes
  (POST "/api/v1/push/:file-path"
        request
        push-file)
  (GET "/api/v1/pull/:file-path"
       request
       pull-file)
  (not-found not-found-response))

(defn app [conf]
  ;; Wrap the routes in middleware bottom to top
  ;; WARNING changes to middleware require this def to be re-evaluated
  ;; since it is used by reference in start-server!
  (-> #'routes ;; use #' to reference routes instead of a value
      (append-conf conf)
      log-request
      ;; parse-json-body
      ;; This must go before parse-json-body as it will overwrite the
      ;; body
      keyword-wrap-params))

(defn start-server!
  [conf]
  ;; use #' to reference app instead of a value so we can dynamically
  ;; reload running code
  (reset!
   server
   (run-server (#'app conf)
               {:port (Integer. (settings/get-or-throw conf "server.port"))})))

(defn stop-server!
  "Shut down the server with optional wait time"
  [& wait-ms]
  (when-not (nil? @server)
    (@server :timeout (or wait-ms 0))
    (reset! server nil)))

(defn restart-server!
  [conf]
  (stop-server!)
  (start-server! conf))

(defn -main
  "Initializes the server with the config-file. Config should be a .properties
   file. See the config directory for an example"
  [config-file]
  (let [conf (settings/load-properties config-file)]
    ;; Initialize logging level and path
    (settings/set-log-level! (settings/get-or-throw conf "logging.level"))
    (when-let [path (get conf "logging.path")] (settings/set-log-path! path))
    ;; Start the server
    (start-server! conf)))
