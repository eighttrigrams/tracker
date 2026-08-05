(ns et.tr.ui.state.journals
  (:require [ajax.core :refer [GET POST]]
            [clojure.string :as str]
            [reagent.core :as r]
            [et.tr.filters :as filters]
            [et.tr.ui.api :as api]
            [et.tr.ui.constants :refer [CATEGORY-TYPE-PERSON CATEGORY-TYPE-PLACE CATEGORY-TYPE-PROJECT CATEGORY-TYPE-GOAL]]
            [et.tr.ui.state.exclusions :as exclusions]
            [et.tr.ui.state.category-filters :as category-filters]))

(defonce *journals-page-state (r/atom {:expanded-journal nil
                                        :confirm-delete-journal nil
                                        :filter-search ""
                                        :fetch-request-id 0}))

(defn- ids->names [ids collection]
  (let [id-set (set ids)
        matching (filter #(contains? id-set (:id %)) collection)]
    (mapv :name matching)))

(defn fetch-journals [app-state auth-headers opts]
  (let [request-id (:fetch-request-id (swap! *journals-page-state update :fetch-request-id inc))
        {:keys [search-term context strict ]} opts
        category-params (category-filters/query-string app-state opts)
        excluded-params (exclusions/query-params app-state)
        url (cond-> "/api/journals?"
              (seq search-term) (str "q=" (js/encodeURIComponent search-term) "&")
              context (str "context=" (name context) "&")
              strict (str "strict=true&")
              (seq category-params) (str category-params)
              (seq excluded-params) (str (str/join "&" excluded-params) "&"))]
    (GET url
      {:response-format :json
       :keywords? true
       :headers (auth-headers)
       :handler (fn [journals]
                  (when (= request-id (:fetch-request-id @*journals-page-state))
                    (swap! app-state assoc :journals journals)))
       :error-handler (fn [_]
                        (when (= request-id (:fetch-request-id @*journals-page-state))
                          (swap! app-state assoc :journals [])))})))

(defn add-journal [app-state auth-headers current-scope-fn title schedule-type on-success fetch-fn]
  (api/post-json "/api/journals"
    {:title title :scope (current-scope-fn) :schedule-type schedule-type}
    (auth-headers)
    (fn [_]
      (fetch-fn)
      (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to add journal")))))

(defn update-journal [app-state auth-headers journal-id title description tags expected-modified-at on-success on-error]
  (api/put-json (str "/api/journals/" journal-id)
    (cond-> {:title title :description description :tags tags}
      expected-modified-at (assoc :expected-modified-at expected-modified-at))
    (auth-headers)
    (fn [result]
      (swap! app-state update :journals
             (fn [journals]
               (mapv #(if (= (:id %) journal-id)
                        (merge % result)
                        %)
                     journals)))
      (when on-success (on-success)))
    (or on-error
        (fn [resp]
          (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update journal"))))))

(defn delete-journal [app-state auth-headers journal-id]
  (api/delete-simple (str "/api/journals/" journal-id)
    (auth-headers)
    (fn [_]
      (swap! app-state update :journals
             (fn [journals] (filterv #(not= (:id %) journal-id) journals)))
      (swap! *journals-page-state assoc :confirm-delete-journal nil))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to delete journal"))
      (swap! *journals-page-state assoc :confirm-delete-journal nil))))

(defn create-entry-for-journal [app-state auth-headers fetch-fn journal-id date]
  (api/post-json (str "/api/journals/" journal-id "/create-entry")
    {:date date}
    (auth-headers)
    (fn [_]
      (fetch-fn))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to create entry")))))

(defn set-journal-scope [app-state auth-headers journal-id scope]
  (api/put-json (str "/api/journals/" journal-id "/scope")
    {:scope scope}
    (auth-headers)
    (fn [result]
      (swap! app-state update :journals
             (fn [journals]
               (let [mode (:work-private-mode @app-state)
                     strict? (:strict-mode @app-state)]
                 (->> journals
                      (mapv #(if (= (:id %) journal-id)
                               (assoc % :scope (:scope result))
                               %))
                      (filterv #(filters/matches-scope? % mode strict?)))))))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update scope")))))

(defn categorize-journal [app-state auth-headers fetch-fn journal-id category-type category-id]
  (api/post-json (str "/api/journals/" journal-id "/categorize")
    {:category-type category-type :category-id category-id}
    (auth-headers)
    (fn [_] (fetch-fn))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to categorize journal")))))

(defn uncategorize-journal [app-state auth-headers fetch-fn journal-id category-type category-id]
  (api/delete-json (str "/api/journals/" journal-id "/categorize")
    {:category-type category-type :category-id category-id}
    (auth-headers)
    (fn [_] (fetch-fn))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to uncategorize journal")))))

(defn set-expanded-journal [id]
  (swap! *journals-page-state assoc :expanded-journal id)
  (when (nil? id)
    (js/setTimeout #(when-let [el (.getElementById js/document "resources-filter-search")]
                      (.focus el #js {:preventScroll true})) 0)))

(defn set-confirm-delete-journal [journal]
  (swap! *journals-page-state assoc :confirm-delete-journal journal))

(defn clear-confirm-delete-journal []
  (swap! *journals-page-state assoc :confirm-delete-journal nil))

(defn set-filter-search [fetch-fn search-term]
  (swap! *journals-page-state assoc :filter-search search-term)
  (fetch-fn))

(defn reset-journals-page-view-state! []
  (swap! *journals-page-state assoc
         :expanded-journal nil))
