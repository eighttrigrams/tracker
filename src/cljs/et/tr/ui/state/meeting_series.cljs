(ns et.tr.ui.state.meeting-series
  (:require [ajax.core :refer [GET POST]]
            [clojure.string :as str]
            [reagent.core :as r]
            [et.tr.ui.api :as api]
            [et.tr.ui.constants :refer [CATEGORY-TYPE-PERSON CATEGORY-TYPE-PLACE CATEGORY-TYPE-PROJECT CATEGORY-TYPE-GOAL]]))

(defonce *meeting-series-page-state (r/atom {:expanded-series nil
                                              :editing-series nil
                                              :confirm-delete-series nil
                                              :filter-search ""
                                              :fetch-request-id 0}))

(defn- ids->names [ids collection]
  (let [id-set (set ids)
        matching (filter #(contains? id-set (:id %)) collection)]
    (mapv :name matching)))

(defn fetch-meeting-series [app-state auth-headers opts]
  (let [request-id (:fetch-request-id (swap! *meeting-series-page-state update :fetch-request-id inc))
        {:keys [search-term context strict filter-people filter-places filter-projects filter-goals]} opts
        people-names (when (seq filter-people) (ids->names filter-people (:people @app-state)))
        place-names (when (seq filter-places) (ids->names filter-places (:places @app-state)))
        project-names (when (seq filter-projects) (ids->names filter-projects (:projects @app-state)))
        goal-names (when (seq filter-goals) (ids->names filter-goals (:goals @app-state)))
        url (cond-> "/api/meeting-series?"
              (seq search-term) (str "q=" (js/encodeURIComponent search-term) "&")
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
       :handler (fn [series]
                  (when (= request-id (:fetch-request-id @*meeting-series-page-state))
                    (swap! app-state assoc :meeting-series series)))
       :error-handler (fn [_]
                        (when (= request-id (:fetch-request-id @*meeting-series-page-state))
                          (swap! app-state assoc :meeting-series [])))})))

(defn add-meeting-series [app-state auth-headers current-scope-fn title on-success fetch-fn]
  (api/post-json "/api/meeting-series"
    {:title title :scope (current-scope-fn)}
    (auth-headers)
    (fn [_]
      (fetch-fn)
      (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to add meeting series")))))

(defn update-meeting-series [app-state auth-headers series-id title description tags on-success]
  (api/put-json (str "/api/meeting-series/" series-id)
    {:title title :description description :tags tags}
    (auth-headers)
    (fn [result]
      (swap! app-state update :meeting-series
             (fn [series]
               (mapv #(if (= (:id %) series-id)
                        (merge % result)
                        %)
                     series)))
      (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update meeting series")))))

(defn delete-meeting-series [app-state auth-headers series-id]
  (api/delete-simple (str "/api/meeting-series/" series-id)
    (auth-headers)
    (fn [_]
      (swap! app-state update :meeting-series
             (fn [series] (filterv #(not= (:id %) series-id) series)))
      (swap! *meeting-series-page-state assoc :confirm-delete-series nil))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to delete meeting series"))
      (swap! *meeting-series-page-state assoc :confirm-delete-series nil))))

(defn set-meeting-series-scope [app-state auth-headers series-id scope]
  (api/put-json (str "/api/meeting-series/" series-id "/scope")
    {:scope scope}
    (auth-headers)
    (fn [result]
      (swap! app-state update :meeting-series
             (fn [series]
               (mapv #(if (= (:id %) series-id)
                        (assoc % :scope (:scope result))
                        %)
                     series))))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update scope")))))

(defn create-meeting-for-series
  ([app-state auth-headers fetch-fn series-id date time]
   (create-meeting-for-series app-state auth-headers fetch-fn series-id date time nil))
  ([app-state auth-headers fetch-fn series-id date time on-success]
   (api/post-json (str "/api/meeting-series/" series-id "/create-meeting")
     {:date date :time time}
     (auth-headers)
     (fn [_]
       (fetch-fn)
       (when on-success (on-success)))
     (fn [resp]
       (swap! app-state assoc :error (get-in resp [:response :error] "Failed to create meeting"))))))

(defn set-meeting-series-schedule [app-state auth-headers series-id schedule-days schedule-time schedule-mode schedule-anchor on-success]
  (api/put-json (str "/api/meeting-series/" series-id "/schedule")
    {:schedule-days schedule-days :schedule-time schedule-time :schedule-mode schedule-mode :schedule-anchor schedule-anchor}
    (auth-headers)
    (fn [result]
      (swap! app-state update :meeting-series
             (fn [series]
               (mapv #(if (= (:id %) series-id)
                        (merge % result)
                        %)
                     series)))
      (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update schedule")))))

(defn categorize-meeting-series [app-state auth-headers fetch-fn series-id category-type category-id]
  (api/post-json (str "/api/meeting-series/" series-id "/categorize")
    {:category-type category-type :category-id category-id}
    (auth-headers)
    (fn [_] (fetch-fn))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to categorize meeting series")))))

(defn uncategorize-meeting-series [app-state auth-headers fetch-fn series-id category-type category-id]
  (api/delete-json (str "/api/meeting-series/" series-id "/categorize")
    {:category-type category-type :category-id category-id}
    (auth-headers)
    (fn [_] (fetch-fn))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to uncategorize meeting series")))))

(defn- categorize-meeting-series-batch [auth-headers series-id category-type ids]
  (doseq [id ids]
    (api/post-json (str "/api/meeting-series/" series-id "/categorize")
      {:category-type category-type :category-id id}
      (auth-headers)
      (fn [_]))))

(defn add-meeting-series-with-categories [app-state auth-headers fetch-fn current-scope-fn title categories on-success]
  (POST "/api/meeting-series"
    {:params {:title title :scope (current-scope-fn)}
     :format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [series]
                (let [series-id (:id series)
                      {:keys [people places projects goals]} categories]
                  (categorize-meeting-series-batch auth-headers series-id CATEGORY-TYPE-PERSON people)
                  (categorize-meeting-series-batch auth-headers series-id CATEGORY-TYPE-PLACE places)
                  (categorize-meeting-series-batch auth-headers series-id CATEGORY-TYPE-PROJECT projects)
                  (categorize-meeting-series-batch auth-headers series-id CATEGORY-TYPE-GOAL goals)
                  (js/setTimeout fetch-fn 500)
                  (swap! app-state update :meeting-series #(cons series %))
                  (when on-success (on-success))))
     :error-handler (fn [resp]
                      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to add meeting series")))}))

(defn set-expanded-series [id]
  (swap! *meeting-series-page-state assoc :expanded-series id :editing-series nil))

(defn set-editing-series [id]
  (swap! *meeting-series-page-state assoc :editing-series id))

(defn clear-editing-series []
  (swap! *meeting-series-page-state assoc :editing-series nil))

(defn set-confirm-delete-series [series]
  (swap! *meeting-series-page-state assoc :confirm-delete-series series))

(defn clear-confirm-delete-series []
  (swap! *meeting-series-page-state assoc :confirm-delete-series nil))

(defn auto-create-meetings [auth-headers on-success]
  (api/post-json "/api/meeting-series/auto-create" {}
    (auth-headers)
    (fn [result]
      (when (and on-success (seq (:created result)))
        (on-success)))
    (fn [_])))

(defn set-filter-search [fetch-fn search-term]
  (swap! *meeting-series-page-state assoc :filter-search search-term)
  (fetch-fn))

(defn clear-all-meeting-series-filters [fetch-fn]
  (swap! *meeting-series-page-state assoc :filter-search "")
  (fetch-fn))

(defn reset-meeting-series-page-view-state! []
  (swap! *meeting-series-page-state assoc
         :expanded-series nil
         :editing-series nil))
