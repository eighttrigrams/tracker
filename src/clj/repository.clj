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

(defn list-resources [{:keys [selected-context-id q active-search] :as args}]
  (tap> ["args" args selected-context-id])
  #_(prn db-config/config)
  #_{:clj-kondo/ignore [:unresolved-var]}
  (if (= :issues active-search)
    {:issues (map #(dissoc % :searchable) (search/search-issues db-config/config q selected-context-id))}
    {:issues (map #(dissoc % :searchable) (search/search-issues db-config/config "" selected-context-id))
     :contexts (map #(dissoc % :searchable) (search/search-contexts db-config/config ""))}))
