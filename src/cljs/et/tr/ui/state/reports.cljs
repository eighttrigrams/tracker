(ns et.tr.ui.state.reports
  (:require [ajax.core :refer [GET]]
            [clojure.string :as str]
            [reagent.core :as r]))

(defonce *reports-page-state (r/atom {:expanded-task nil
                                      :expanded-meet nil
                                      :expanded-journal-entry nil
                                      :fetch-request-id 0}))

(defn- ids->names [ids collection]
  (let [id-set (set ids)
        matching (filter #(contains? id-set (:id %)) collection)]
    (mapv :name matching)))

(defn fetch-reports [app-state auth-headers opts]
  (let [request-id (:fetch-request-id (swap! *reports-page-state update :fetch-request-id inc))
        {:keys [context strict filter-people filter-places filter-projects filter-goals]} opts
        people-names (when (seq filter-people) (ids->names filter-people (:people @app-state)))
        place-names (when (seq filter-places) (ids->names filter-places (:places @app-state)))
        project-names (when (seq filter-projects) (ids->names filter-projects (:projects @app-state)))
        goal-names (when (seq filter-goals) (ids->names filter-goals (:goals @app-state)))
        url (cond-> "/api/reports?"
              context (str "context=" (name context) "&")
              strict (str "strict=true&")
              (seq people-names) (str "people=" (js/encodeURIComponent (str/join "," people-names)) "&")
              (seq place-names) (str "places=" (js/encodeURIComponent (str/join "," place-names)) "&")
              (seq project-names) (str "projects=" (js/encodeURIComponent (str/join "," project-names)) "&")
              (seq goal-names) (str "goals=" (js/encodeURIComponent (str/join "," goal-names)) "&"))]
    (GET url
      {:response-format :json
       :keywords? true
       :headers (auth-headers)
       :handler (fn [data]
                  (when (= request-id (:fetch-request-id @*reports-page-state))
                    (swap! app-state assoc :reports-data data)))
       :error-handler (fn [_]
                        (when (= request-id (:fetch-request-id @*reports-page-state))
                          (swap! app-state assoc :reports-data {:tasks [] :meets [] :journal_entries []})))})))

(defn reset-reports-page-view-state! []
  (swap! *reports-page-state assoc
         :expanded-task nil
         :expanded-meet nil
         :expanded-journal-entry nil))
