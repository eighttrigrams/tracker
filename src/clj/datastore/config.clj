(ns datastore.config
  (:require [mount.core :as mount]))

(defn ds []
  (read-string (slurp "./db-config.edn")))

(mount/defstate config
  :start (ds)
  :stop nil)
