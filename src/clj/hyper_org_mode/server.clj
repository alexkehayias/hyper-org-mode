(ns hyper-org-mode.server
  (:require [compojure.core :refer [defroutes GET POST]]
            [compojure.route :refer [not-found]]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]]
            [ring.util.response :refer [file-response]]
            [org.httpkit.server :refer [run-server]]
            [cheshire.core :refer [generate-string]]
            [hyper-org-mode.settings :as settings]
            [hyper-org-mode.middleware :refer [parse-json-body
                                               input-stream->str
                                               log-request
                                               append-conf
                                               append-state
                                               keyword-wrap-params]]))

(comment
  "curl -F "proposed=@todo.org" -F "previous=@test.org" 127.0.0.1:1986/api/v1/pus
h/todo.org"
  "curl -X get 127.0.0.1:1986/api/v1/pull/todo.org")

;; TODO implement change log of master

(defn storage-exists? [path]
  (.exists (clojure.java.io/file (str path "/" "./.git"))))

(defn get-merge-conflict [current previous proposed]
  (:out (clojure.java.shell/sh "diff3" "-m"
                               "-L" "current" "-L" "previous" "-L" "proposed"
                               (.getAbsolutePath current)
                               (.getAbsolutePath previous)
                               (.getAbsolutePath proposed))))

(defn merge-files [current previous proposed]
  (println "MERGING" (map #(.getAbsolutePath %) [current previous proposed]))
  ;; Throw an exception if there is a conflict with master
  (when-not (clojure.string/blank?
             (:out (clojure.java.shell/sh "diff3" "-A"
                                          (.getAbsolutePath current)
                                          (.getAbsolutePath previous)
                                          (.getAbsolutePath proposed))))
    (throw (Exception. "Merge conflict")))
  (:out (clojure.java.shell/sh "diff3" "-m"
                            (.getAbsolutePath current)
                            (.getAbsolutePath previous)
                            (.getAbsolutePath proposed))))

(defn commit! [storage-path file-name state file-map]
  ;; Take change, previous, master and merge them
  ;; TODO get the current file and do the 3 way merge
  (let [{:keys [proposed previous]} file-map]
    (try (do (swap! state
                    #(do (spit (str storage-path "/" file-name)
                               (merge-files (get % file-name (:tempfile proposed))
                                            (:tempfile previous)
                                            (:tempfile proposed)))
                         (assoc % file-name (clojure.java.io/file (str storage-path "/" file-name)))))
             [:success file-name])
         (catch Exception e
           [:fail (get-merge-conflict
                   (get @state file-name (:tempfile proposed))
                   (:tempfile previous)
                   (:tempfile proposed))]))))

(def not-found-response
  {:status_code 404
   :body "{\"message\": \"Not found\", \"status_code\": 404}"})

(def ok-response
  {:status_code 404
   :body "{\"message\": \"ok\", \"status_code\": 200}"})

(defn conflict-response [conflict]
  {:status_code 301
   :body conflict})

(def bad-response
  {:status_code 301
   :body "{\"message\": \"bad request\", \"status_code\": 301}"})

(defn push-file [{:keys [params multipart-params body conf state]}]
  "Example:
  curl -X post -d @./todo.org 127.0.0.1:1986/api/v1/push/todo.org"
  ;; TODO merge the file into the master version of the file
  ;; if the merge fails return a fail response
  (println "FIle path" (:file-path params))
  (let [[status result] (commit! (settings/get-or-throw conf "org.storage-path")
                                 (:file-path params)
                                 state
                                 multipart-params)]
    (if (= status :success)
      ok-response
      (conflict-response result))))

(defn pull-file [{:keys [params conf state]}]
  (if-let [file (get @state (:file-path params))]
    (file-response (str (get conf "org.storage-path") "/" (:file-path params)))
    not-found-response))

(defonce server (atom nil))

(defroutes routes
  (POST "/api/v1/push/:file-path"
        request
        push-file)
  (GET "/api/v1/pull/:file-path"
       request
       pull-file)
  (not-found not-found-response))

(defn app [conf state]
  ;; Wrap the routes in middleware bottom to top
  ;; WARNING changes to middleware require this def to be re-evaluated
  ;; since it is used by reference in start-server!
  (-> #'routes ;; use #' to reference routes instead of a value
      (append-conf conf)
      (append-state state)
      log-request
      keyword-wrap-params
      wrap-multipart-params))

(defn start-server!
  [conf state]
  ;; use #' to reference app instead of a value so we can dynamically
  ;; reload running code
  (reset!
   server
   (run-server (#'app conf state)
               {:port (Integer. (settings/get-or-throw conf "server.port"))})))

(defn stop-server!
  "Shut down the server with optional wait time"
  [& wait-ms]
  (when-not (nil? @server)
    (@server :timeout (or wait-ms 0))
    (reset! server nil)))

(defn restart-server!
  [conf state]
  (stop-server!)
  (start-server! conf state))

(defn -main
  "Initializes the server with the config-file. Config should be a .properties
   file. See the config directory for an example"
  [config-file]
  (let [conf (settings/load-properties config-file)
        storage-path (settings/get-or-throw conf "org.storage-path")
        state (atom (reduce #(into %1 {(.getName %2) %2}) {}
                            (file-seq (clojure.java.io/file storage-path))))]
    ;; Initialize logging level and path
    (settings/set-log-level! (settings/get-or-throw conf "server.log-level"))
    (when-let [path (get conf "logging.path")]
      (settings/set-log-path! path))
    ;; Start the server
    (start-server! conf state)))
