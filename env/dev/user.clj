(ns user
  (:require [mount.core :as mount]
            server
            [datastore.config :as config]))

(def ds (config/ds))

(defn start []
  (mount/start))
