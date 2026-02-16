(ns et.tr.ui.state.meets
  (:require [ajax.core :refer [GET]]
            [clojure.string :as str]
            [reagent.core :as r]
            [et.tr.ui.api :as api]))

(defonce *meets-page-state (r/atom {:expanded-meet nil
                                     :editing-meet nil
                                     :confirm-delete-meet nil
                                     :filter-search ""
                                     :importance-filter nil
                                     :fetch-request-id 0}))

(defn- ids->names [ids collection]
  (let [id-set (set ids)
        matching (filter #(contains? id-set (:id %)) collection)]
    (mapv :name matching)))

(defn fetch-meets [app-state auth-headers opts]
  (let [request-id (:fetch-request-id (swap! *meets-page-state update :fetch-request-id inc))
        {:keys [search-term importance context strict filter-people filter-places filter-projects]} opts
        people-names (when (seq filter-people) (ids->names filter-people (:people @app-state)))
        place-names (when (seq filter-places) (ids->names filter-places (:places @app-state)))
        project-names (when (seq filter-projects) (ids->names filter-projects (:projects @app-state)))
        url (cond-> "/api/meets?"
              (seq search-term) (str "q=" (js/encodeURIComponent search-term) "&")
              importance (str "importance=" (name importance) "&")
              context (str "context=" (name context) "&")
              strict (str "strict=true&")
              (seq people-names) (str "people=" (js/encodeURIComponent (str/join "," people-names)) "&")
              (seq place-names) (str "places=" (js/encodeURIComponent (str/join "," place-names)) "&")
              (seq project-names) (str "projects=" (js/encodeURIComponent (str/join "," project-names)) "&"))]
    (GET url
      {:response-format :json
       :keywords? true
       :headers (auth-headers)
       :handler (fn [meets]
                  (when (= request-id (:fetch-request-id @*meets-page-state))
                    (swap! app-state assoc :meets meets)))
       :error-handler (fn [_]
                        (when (= request-id (:fetch-request-id @*meets-page-state))
                          (swap! app-state assoc :meets [])))})))

(defn add-meet [app-state auth-headers current-scope-fn title on-success fetch-meets-fn]
  (api/post-json "/api/meets"
    {:title title :scope (current-scope-fn)}
    (auth-headers)
    (fn [_]
      (fetch-meets-fn)
      (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to add meet")))))

(defn update-meet [app-state auth-headers meet-id title description tags on-success]
  (api/put-json (str "/api/meets/" meet-id)
    {:title title :description description :tags tags}
    (auth-headers)
    (fn [result]
      (swap! app-state update :meets
             (fn [meets]
               (mapv #(if (= (:id %) meet-id)
                        (merge % result)
                        %)
                     meets)))
      (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update meet")))))

(defn delete-meet [app-state auth-headers meet-id]
  (api/delete-simple (str "/api/meets/" meet-id)
    (auth-headers)
    (fn [_]
      (swap! app-state update :meets
             (fn [meets] (filterv #(not= (:id %) meet-id) meets)))
      (swap! *meets-page-state assoc :confirm-delete-meet nil))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to delete meet"))
      (swap! *meets-page-state assoc :confirm-delete-meet nil))))

(defn set-meet-scope [app-state auth-headers meet-id scope]
  (api/put-json (str "/api/meets/" meet-id "/scope")
    {:scope scope}
    (auth-headers)
    (fn [result]
      (swap! app-state update :meets
             (fn [meets]
               (mapv #(if (= (:id %) meet-id)
                        (assoc % :scope (:scope result))
                        %)
                     meets))))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update scope")))))

(defn set-meet-importance [app-state auth-headers meet-id importance]
  (api/put-json (str "/api/meets/" meet-id "/importance")
    {:importance importance}
    (auth-headers)
    (fn [result]
      (swap! app-state update :meets
             (fn [meets]
               (mapv #(if (= (:id %) meet-id)
                        (assoc % :importance (:importance result))
                        %)
                     meets))))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update importance")))))

(defn categorize-meet [app-state auth-headers fetch-meets-fn meet-id category-type category-id]
  (api/post-json (str "/api/meets/" meet-id "/categorize")
    {:category-type category-type :category-id category-id}
    (auth-headers)
    (fn [_] (fetch-meets-fn))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to categorize meet")))))

(defn uncategorize-meet [app-state auth-headers fetch-meets-fn meet-id category-type category-id]
  (api/delete-json (str "/api/meets/" meet-id "/categorize")
    {:category-type category-type :category-id category-id}
    (auth-headers)
    (fn [_] (fetch-meets-fn))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to uncategorize meet")))))

(defn set-expanded-meet [id]
  (swap! *meets-page-state assoc :expanded-meet id :editing-meet nil))

(defn set-editing-meet [id]
  (swap! *meets-page-state assoc :editing-meet id))

(defn clear-editing-meet []
  (swap! *meets-page-state assoc :editing-meet nil))

(defn set-confirm-delete-meet [meet]
  (swap! *meets-page-state assoc :confirm-delete-meet meet))

(defn clear-confirm-delete-meet []
  (swap! *meets-page-state assoc :confirm-delete-meet nil))

(defn set-filter-search [fetch-meets-fn search-term]
  (swap! *meets-page-state assoc :filter-search search-term)
  (fetch-meets-fn))

(defn set-importance-filter [fetch-meets-fn level]
  (swap! *meets-page-state assoc :importance-filter level)
  (fetch-meets-fn))

(defn clear-all-meet-filters [fetch-meets-fn]
  (swap! *meets-page-state assoc :filter-search "" :importance-filter nil)
  (fetch-meets-fn))

(defn reset-meets-page-view-state! []
  (swap! *meets-page-state assoc
         :expanded-meet nil
         :editing-meet nil))
