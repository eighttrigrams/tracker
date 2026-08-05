(ns et.tr.ui.state.reports
  (:require [ajax.core :refer [GET]]
            [clojure.string :as str]
            [reagent.core :as r]
            [et.tr.ui.state.exclusions :as exclusions]
            [et.tr.ui.state.category-filters :as category-filters]))

(defonce *reports-page-state (r/atom {:expanded-task nil
                                      :expanded-meet nil
                                      :expanded-journal-entry nil
                                      :expanded-issue nil
                                      :week-offset 0
                                      :week-limit 1
                                      :fetch-request-id 0}))

(defn- ids->names [ids collection]
  (let [id-set (set ids)
        matching (filter #(contains? id-set (:id %)) collection)]
    (mapv :name matching)))

(defn fetch-reports [app-state auth-headers opts]
  (let [request-id (:fetch-request-id (swap! *reports-page-state update :fetch-request-id inc))
        {:keys [context strict items-filter 
                week-offset week-limit]} opts
        category-params (category-filters/query-string app-state opts)
        excluded-params (exclusions/query-params app-state)
        url (cond-> "/api/reports?"
              context (str "context=" (name context) "&")
              strict (str "strict=true&")
              (and items-filter (not= items-filter :all)) (str "items=" (name items-filter) "&")
              (seq category-params) (str category-params)
              (seq excluded-params) (str (str/join "&" excluded-params) "&")
              true (str "weekOffset=" (or week-offset 0) "&")
              true (str "weekLimit=" (or week-limit 1) "&"))]
    (GET url
      {:response-format :json
       :keywords? true
       :headers (auth-headers)
       :handler (fn [data]
                  (when (= request-id (:fetch-request-id @*reports-page-state))
                    (swap! app-state assoc :reports-data data)))
       :error-handler (fn [_]
                        (when (= request-id (:fetch-request-id @*reports-page-state))
                          (swap! app-state assoc :reports-data {:issues [] :tasks [] :meets [] :journal_entries []})))})))

(defn reset-reports-page-view-state! []
  (swap! *reports-page-state assoc
         :expanded-task nil
         :expanded-meet nil
         :expanded-journal-entry nil
         :expanded-issue nil))
