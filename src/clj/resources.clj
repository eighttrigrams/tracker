(ns resources
  (:require [mount.core :as mount]))

(mount/defstate resources
  :start (do
           (tap> [:resources :up 2])
           [{:id   1
             :name "one"}
            {:id   2
             :name "two"}
            {:id        3
             :name      "three"
             :protected true}])
  :stop (do 
          (tap> [:resources :down])
          nil))

(defn list-resources [_arg]
  [1 2 3]) ;; edit
