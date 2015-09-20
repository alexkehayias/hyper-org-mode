(ns hyper-org-mode.settings
  (:require [clojure.java.io :as io]
            [taoensso.timbre :as log]))


(defn load-properties
  "Convert a .properties file into a hash-map

   via http://stackoverflow.com/questions/7777882/loading-configuration
   -file-in-clojure-as-data-structure"
  [file-name]
  (with-open [^java.io.Reader reader (clojure.java.io/reader file-name)]
    (let [props (java.util.Properties.)]
      (.load props reader)
      (into {} props))))

(defn set-log-path!
  "Setup logging to file to the specified path"
  [path]
  (log/info "Setting up logging to file...")
  (log/set-config! [:appenders :spit :enabled?] true)
  (log/set-config! [:shared-appender-config :spit-filename] path))

(defn set-log-level!
  "Sets the logging level in timbre"
  [level]
  (log/info "Setting log level to" level)
  (log/set-level! (keyword level)))

(defn get-or-throw
  "Returns the value of key in hashmap conf. Throws and exception if
   the return value is nil."
  [conf key]
  (if-let [val (get conf key)]
    val
    (throw (Exception.
            (format "Configuration setting \"%s\" not found" key)))))
