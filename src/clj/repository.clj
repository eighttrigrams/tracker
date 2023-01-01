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

(defn get-issues [q]
  (let [db (:db config/config)]
    (search/search-issues db {:q q})))

(defn get-contexts [q]
  (let [db (:db config/config)]
    (search/search-contexts db q)))

(defn list-resources [{:keys [q 
                              active-search 
                              show-events?
                              issue-to-insert
                              issue-and-related-issues-to-update
                              context-and-secondary-contexts-to-update
                              issue-to-update-description-of
                              context-to-update-description-of
                              selected-issue
                              selected-context
                              context-to-fetch
                              issue-to-fetch
                              link-issue-contexts
                              do-cycle-search-mode
                              do-delete-issue
                              do-reprioritize-issue
                              do-mark-issue-important] 
                       :as opts}]
  #_{:clj-kondo/ignore [:unresolved-var]}
  (let [db (:db config/config)]
    (cond
      link-issue-contexts 
      {:selected-issue  (datastore/link-issue-contexts db selected-issue link-issue-contexts)
       :issues (search/search-issues db opts)}
      do-reprioritize-issue
      (do (datastore/reprioritize-issue db selected-issue) 
          {:issues (search/search-issues db opts)})
      do-mark-issue-important
      {:selected-issue (datastore/mark-issue-important db selected-issue) 
       :issues (search/search-issues db opts)}
      do-delete-issue
      (do (datastore/delete-issue db selected-issue)
          {:issues (search/search-issues db opts)})
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
      issue-and-related-issues-to-update
      {:selected-issue (datastore/update-issue db issue-and-related-issues-to-update)
       :issues         (search/search-issues db opts)}
      context-and-secondary-contexts-to-update
      {:selected-context (datastore/update-context db context-and-secondary-contexts-to-update)
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
