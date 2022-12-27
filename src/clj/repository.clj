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
                              issue-to-insert
                              issue-to-update
                              context-to-update
                              issue-to-update-description-of
                              context-to-update-description-of
                              selected-context
                              context-to-fetch
                              issue-to-fetch
                              do-cycle-search-mode] 
                       :as opts}]
  #_{:clj-kondo/ignore [:unresolved-var]}
  (let [db (:db config/config)]
    (cond
      do-cycle-search-mode
      (let [selected-context (datastore/cycle-search-mode db selected-context)]
        {:selected-context selected-context
         :issues (search/search-issues db (assoc opts :selected-context selected-context))})
      issue-to-insert
      {:selected-issue (datastore/new-issue db issue-to-insert (:id selected-context))
       :issues         (search/search-issues db opts)}
      issue-to-update-description-of
      {:selected-issue (datastore/update-issue-description db issue-to-update-description-of)
       :issues         (search/search-issues db opts)}
      context-to-update-description-of
      {:selected-context (datastore/update-context-description db context-to-update-description-of)}
      issue-to-update
      {:selected-issue (datastore/update-issue db issue-to-update)
       :issues         (search/search-issues db opts)}
      context-to-update
      {:selected-context (datastore/update-context db context-to-update)
       :issues           (search/search-issues db opts)} ;; maybe not necessary (yet)
      issue-to-fetch
      {:selected-issue      (datastore/get-issue db issue-to-fetch)
       :issues              (when active-search (search/search-issues db opts))
       :quit-active-search? (boolean active-search)}
      context-to-fetch
      (let [selected-context (datastore/get-context db context-to-fetch)]
        {:selected-context    selected-context
         :issues              (search/search-issues db (assoc opts :selected-context selected-context))
         :quit-active-search? (boolean active-search)})
      show-events?
      {:issues   (search/search-issues db opts)
       :contexts []}
      (= :issues active-search)
      {:issues (search/search-issues db opts)}
      (= :contexts active-search)
      {:contexts (search/search-contexts db q)}
      :else {:issues   (search/search-issues db opts)
             :contexts (search/search-contexts db "")})))
