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
                              cmd
                              arg
                              active-search 
                              show-events?
                              issue-and-related-issues-to-update
                              context-and-secondary-contexts-to-update
                              issue-to-update-description-of
                              context-to-update-description-of
                              selected-issue
                              selected-context
                              selected-secondary-contexts-ids
                              context-to-fetch
                              issue-to-fetch
                              link-issue-contexts] 
                       :as   opts}]
  #_{:clj-kondo/ignore [:unresolved-var]}
  (merge 
   {:active-search                   active-search
    :selected-secondary-contexts-ids selected-secondary-contexts-ids}
   (let [db (:db config/config)]
     (cond
       link-issue-contexts
       {:selected-issue (datastore/link-issue-contexts db selected-issue link-issue-contexts)
        :issues         (search/search-issues db opts)}
       (= :reprioritize-issue cmd)
       (do (datastore/reprioritize-issue db selected-issue)
           {:issues (search/search-issues db opts)})
       (= :mark-issue-important cmd)
       {:selected-issue (datastore/mark-issue-important db selected-issue)
        :issues         (search/search-issues db opts)}
       (= :delete-issue cmd)
       (do (datastore/delete-issue db arg)
           {:issues (search/search-issues db opts)})
       (= :delete-context cmd)
       (do (datastore/delete-context db arg)
           {:issues   (search/search-issues db opts)
            :contexts (search/search-contexts db "")})
       (= :do-cycle-search-mode cmd)
       (let [selected-context (datastore/cycle-search-mode db selected-context)]
         {:selected-context selected-context
          :issues           (search/search-issues db (assoc opts :selected-context selected-context))})
       (= :insert-issue cmd)
       (let [selected-issue (datastore/new-issue db arg
                                                 (:id selected-context)
                                                 selected-secondary-contexts-ids)]
         {:selected-issue selected-issue
          :issues         (search/search-issues db (assoc opts :selected-issue selected-issue))})
       (= :insert-context cmd)
       {:selected-context (datastore/new-context db arg)
        :issues           []}
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
       {:selected-issue (datastore/get-issue db issue-to-fetch)
        :issues         (when active-search (search/search-issues db opts))
        :active-search  nil}
       context-to-fetch
       (let [selected-context (datastore/get-context db context-to-fetch)]
         {:selected-context selected-context
          :issues           (search/search-issues db (assoc opts :selected-context selected-context))
          :active-search    nil})
       show-events?
       {:issues   (search/search-issues db opts)
        :contexts []
        :selected-secondary-contexts-ids #{}}
       (= :issues active-search)
       {:issues (search/search-issues db opts)}
       (= :contexts active-search)
       {:contexts (search/search-contexts db q)}
       (= :do-change-secondary-contexts-selection cmd)
       {:issues (search/search-issues db opts)}
       (= :do-change-secondary-contexts-unassigned-selected cmd)
       {:issues (search/search-issues db opts)}
       (= :do-change-secondary-contexts-inverted cmd)
       {:issues (search/search-issues db opts)}
       (= :deselect-secondary-contexts cmd)
       {:issues                          (search/search-issues db opts)
        :contexts                        (search/search-contexts db "")
        :selected-secondary-contexts-ids #{}}
       :else {:issues   (search/search-issues db opts)
              :contexts (search/search-contexts db "")}))))
