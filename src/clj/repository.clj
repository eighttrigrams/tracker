(ns repository ;; TODO rename to controller or something, because it contains (data-driven) logic
  (:require [mount.core :as mount]
            [datastore.config :as config]
            datastore
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

(defn list-resources [{:keys [q 
                              active-search 
                              show-events? 
                              issue-to-update
                              issue-to-fetch] 
                       :as opts}]
  #_{:clj-kondo/ignore [:unresolved-var]}
  (let [db (:db config/config)]
    (cond
      issue-to-update
      {:selected-issue (datastore/update-issue db issue-to-update)
       :issues         (search/search-issues db opts)}
      issue-to-fetch
      {:selected-issue      (datastore/get-issue db issue-to-fetch)
       :issues              (when active-search (search/search-issues db opts))
       :quit-active-search? (boolean active-search)}
      show-events?
      {:issues   (search/search-issues db opts)
       :contexts []}
      (= :issues active-search)
      {:issues (search/search-issues db opts)}
      (= :contexts active-search)
      {:contexts (search/search-contexts db q)}
      :else {:issues   (search/search-issues db opts)
             :contexts (search/search-contexts db "")})))

#_{:clj-kondo/ignore [:unresolved-var]}
(defn update-issue-description [id value]
  (datastore/update-issue-description (:db config/config) id value))

#_{:clj-kondo/ignore [:unresolved-var]}
(defn update-context-description [id value]
  (datastore/update-context-description (:db config/config) id value))

#_{:clj-kondo/ignore [:unresolved-var]}
(defn new-issue [value context-id]
  (datastore/new-issue (:db config/config) value context-id))
