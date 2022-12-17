(ns repository
  (:require [mount.core :as mount]
            [datastore.config :as db-config]
            [datastore.search :as search]))

(mount/defstate repository
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
  #_(prn db-config/config)
  #_{:clj-kondo/ignore [:unresolved-var]}
  (map #(dissoc % :searchable) (search/search-issues db-config/config ""))) ;; edit
