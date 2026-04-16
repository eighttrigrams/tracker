(ns et.tr.ui.state.journal-entries
  (:require [ajax.core :refer [GET POST]]
            [clojure.string :as str]
            [reagent.core :as r]
            [et.tr.filters :as filters]
            [et.tr.ui.api :as api]))

(defonce *journal-entries-page-state (r/atom {:expanded-entry nil
                                              :confirm-delete-entry nil
                                              :filter-search ""
                                              :importance-filter nil
                                              :sort-mode :manual
                                              :fetch-request-id 0}))

(defn- ids->names [ids collection]
  (let [id-set (set ids)
        matching (filter #(contains? id-set (:id %)) collection)]
    (mapv :name matching)))

(defn fetch-journal-entries [app-state auth-headers opts]
  (let [request-id (:fetch-request-id (swap! *journal-entries-page-state update :fetch-request-id inc))
        {:keys [search-term importance context strict filter-people filter-places filter-projects filter-goals sort-mode journal-id]} opts
        people-names (when (seq filter-people) (ids->names filter-people (:people @app-state)))
        place-names (when (seq filter-places) (ids->names filter-places (:places @app-state)))
        project-names (when (seq filter-projects) (ids->names filter-projects (:projects @app-state)))
        goal-names (when (seq filter-goals) (ids->names filter-goals (:goals @app-state)))
        url (cond-> "/api/journal-entries?"
              (seq search-term) (str "q=" (js/encodeURIComponent search-term) "&")
              importance (str "importance=" (name importance) "&")
              context (str "context=" (name context) "&")
              strict (str "strict=true&")
              journal-id (str "journalId=" journal-id "&")
              (seq people-names) (str "people=" (js/encodeURIComponent (str/join "," people-names)) "&")
              (seq place-names) (str "places=" (js/encodeURIComponent (str/join "," place-names)) "&")
              (seq project-names) (str "projects=" (js/encodeURIComponent (str/join "," project-names)) "&")
              (seq goal-names) (str "goals=" (js/encodeURIComponent (str/join "," goal-names)) "&")
              sort-mode (str "sortMode=" (name sort-mode) "&"))]
    (GET url
      {:response-format :json
       :keywords? true
       :headers (auth-headers)
       :handler (fn [entries]
                  (when (= request-id (:fetch-request-id @*journal-entries-page-state))
                    (swap! app-state assoc :journal-entries entries)))
       :error-handler (fn [_]
                        (when (= request-id (:fetch-request-id @*journal-entries-page-state))
                          (swap! app-state assoc :journal-entries [])))})))

(defn fetch-today-journal-entries [app-state auth-headers opts]
  (let [{:keys [context strict]} opts
        url (cond-> "/api/journal-entries/today?"
              context (str "context=" (name context) "&")
              strict (str "strict=true&"))]
    (GET url
      {:response-format :json
       :keywords? true
       :headers (auth-headers)
       :handler (fn [entries]
                  (swap! app-state assoc :today-journal-entries entries))
       :error-handler (fn [_]
                        (swap! app-state assoc :today-journal-entries []))})))

(defn add-journal-entry [app-state auth-headers current-scope-fn title on-success fetch-fn]
  (api/post-json "/api/journal-entries"
    {:title title :scope (current-scope-fn)}
    (auth-headers)
    (fn [_]
      (fetch-fn)
      (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to add journal entry")))))

(defn update-journal-entry [app-state auth-headers entry-id title description tags on-success]
  (api/put-json (str "/api/journal-entries/" entry-id)
    {:title title :description description :tags tags}
    (auth-headers)
    (fn [result]
      (let [merge-fn (fn [entries]
                       (mapv #(if (= (:id %) entry-id)
                                (merge % result)
                                %)
                             entries))]
        (swap! app-state (fn [s]
                           (-> s
                               (update :journal-entries merge-fn)
                               (update :today-journal-entries merge-fn)
                               (update-in [:reports-data :journal_entries] merge-fn)))))
      (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update journal entry")))))

(defn delete-journal-entry [app-state auth-headers entry-id]
  (api/delete-simple (str "/api/journal-entries/" entry-id)
    (auth-headers)
    (fn [_]
      (swap! app-state update :journal-entries
             (fn [entries] (filterv #(not= (:id %) entry-id) entries)))
      (swap! *journal-entries-page-state assoc :confirm-delete-entry nil))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to delete journal entry"))
      (swap! *journal-entries-page-state assoc :confirm-delete-entry nil))))

(defn set-journal-entry-scope [app-state auth-headers entry-id scope]
  (api/put-json (str "/api/journal-entries/" entry-id "/scope")
    {:scope scope}
    (auth-headers)
    (fn [result]
      (swap! app-state update :journal-entries
             (fn [entries]
               (let [mode (:work-private-mode @app-state)
                     strict? (:strict-mode @app-state)]
                 (->> entries
                      (mapv #(if (= (:id %) entry-id)
                               (assoc % :scope (:scope result))
                               %))
                      (filterv #(filters/matches-scope? % mode strict?)))))))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update scope")))))

(defn set-journal-entry-importance [app-state auth-headers entry-id importance]
  (api/put-json (str "/api/journal-entries/" entry-id "/importance")
    {:importance importance}
    (auth-headers)
    (fn [result]
      (swap! app-state update :journal-entries
             (fn [entries]
               (mapv #(if (= (:id %) entry-id)
                        (assoc % :importance (:importance result))
                        %)
                     entries))))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update importance")))))

(defn categorize-journal-entry [app-state auth-headers fetch-fn entry-id category-type category-id]
  (api/post-json (str "/api/journal-entries/" entry-id "/categorize")
    {:category-type category-type :category-id category-id}
    (auth-headers)
    (fn [_] (fetch-fn))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to categorize journal entry")))))

(defn uncategorize-journal-entry [app-state auth-headers fetch-fn entry-id category-type category-id]
  (api/delete-json (str "/api/journal-entries/" entry-id "/categorize")
    {:category-type category-type :category-id category-id}
    (auth-headers)
    (fn [_] (fetch-fn))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to uncategorize journal entry")))))

(defn reorder-journal-entry [app-state auth-headers fetch-fn entry-id target-entry-id position]
  (api/post-json (str "/api/journal-entries/" entry-id "/reorder")
    {:target-entry-id target-entry-id :position position}
    (auth-headers)
    (fn [_] (fetch-fn))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to reorder journal entry")))))

(defn set-expanded-entry [id]
  (swap! *journal-entries-page-state assoc :expanded-entry id))

(defn set-confirm-delete-entry [entry]
  (swap! *journal-entries-page-state assoc :confirm-delete-entry entry))

(defn clear-confirm-delete-entry []
  (swap! *journal-entries-page-state assoc :confirm-delete-entry nil))

(defn set-filter-search [fetch-fn search-term]
  (swap! *journal-entries-page-state assoc :filter-search search-term)
  (fetch-fn))

(defn set-sort-mode [fetch-fn mode]
  (swap! *journal-entries-page-state assoc :sort-mode mode)
  (fetch-fn))

(defn set-importance-filter [fetch-fn level]
  (swap! *journal-entries-page-state assoc :importance-filter level)
  (fetch-fn))

(defn reset-journal-entries-page-view-state! []
  (swap! *journal-entries-page-state assoc
         :expanded-entry nil))
