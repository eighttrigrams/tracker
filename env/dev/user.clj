(ns user
  (:require [mount.core :as mount]
            server))

(defn start []
  (mount/start))
