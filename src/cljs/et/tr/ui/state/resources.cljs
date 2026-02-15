(ns et.tr.ui.state.resources
  (:require [ajax.core :refer [GET]]
            [reagent.core :as r]
            [et.tr.ui.api :as api]))

(defonce *resources-page-state (r/atom {:expanded-resource nil
                                        :editing-resource nil
                                        :confirm-delete-resource nil
                                        :filter-search ""
                                        :importance-filter nil
                                        :fetch-request-id 0}))

(defn fetch-resources [app-state auth-headers opts]
  (let [request-id (:fetch-request-id (swap! *resources-page-state update :fetch-request-id inc))
        {:keys [search-term importance context strict]} opts
        url (cond-> "/api/resources?"
              (seq search-term) (str "q=" (js/encodeURIComponent search-term) "&")
              importance (str "importance=" (name importance) "&")
              context (str "context=" (name context) "&")
              strict (str "strict=true&"))]
    (GET url
      {:response-format :json
       :keywords? true
       :headers (auth-headers)
       :handler (fn [resources]
                  (when (= request-id (:fetch-request-id @*resources-page-state))
                    (swap! app-state assoc :resources resources)))
       :error-handler (fn [_]
                        (when (= request-id (:fetch-request-id @*resources-page-state))
                          (swap! app-state assoc :resources [])))})))

(defn add-resource [app-state auth-headers current-scope-fn title link on-success fetch-resources-fn]
  (api/post-json "/api/resources"
    {:title title :link link :scope (current-scope-fn)}
    (auth-headers)
    (fn [_]
      (fetch-resources-fn)
      (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to add resource")))))

(defn update-resource [app-state auth-headers resource-id title link description tags on-success]
  (api/put-json (str "/api/resources/" resource-id)
    {:title title :link link :description description :tags tags}
    (auth-headers)
    (fn [result]
      (swap! app-state update :resources
             (fn [resources]
               (mapv #(if (= (:id %) resource-id)
                        (merge % result)
                        %)
                     resources)))
      (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update resource")))))

(defn delete-resource [app-state auth-headers resource-id]
  (api/delete-simple (str "/api/resources/" resource-id)
    (auth-headers)
    (fn [_]
      (swap! app-state update :resources
             (fn [resources] (filterv #(not= (:id %) resource-id) resources)))
      (swap! *resources-page-state assoc :confirm-delete-resource nil))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to delete resource"))
      (swap! *resources-page-state assoc :confirm-delete-resource nil))))

(defn set-resource-scope [app-state auth-headers resource-id scope]
  (api/put-json (str "/api/resources/" resource-id "/scope")
    {:scope scope}
    (auth-headers)
    (fn [result]
      (swap! app-state update :resources
             (fn [resources]
               (mapv #(if (= (:id %) resource-id)
                        (assoc % :scope (:scope result))
                        %)
                     resources))))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update scope")))))

(defn set-resource-importance [app-state auth-headers resource-id importance]
  (api/put-json (str "/api/resources/" resource-id "/importance")
    {:importance importance}
    (auth-headers)
    (fn [result]
      (swap! app-state update :resources
             (fn [resources]
               (mapv #(if (= (:id %) resource-id)
                        (assoc % :importance (:importance result))
                        %)
                     resources))))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update importance")))))

(defn set-expanded-resource [id]
  (swap! *resources-page-state assoc :expanded-resource id :editing-resource nil))

(defn set-editing-resource [id]
  (swap! *resources-page-state assoc :editing-resource id))

(defn clear-editing-resource []
  (swap! *resources-page-state assoc :editing-resource nil))

(defn set-confirm-delete-resource [resource]
  (swap! *resources-page-state assoc :confirm-delete-resource resource))

(defn clear-confirm-delete-resource []
  (swap! *resources-page-state assoc :confirm-delete-resource nil))

(defn set-filter-search [app-state fetch-resources-fn search-term]
  (swap! *resources-page-state assoc :filter-search search-term)
  (fetch-resources-fn))

(defn set-importance-filter [app-state fetch-resources-fn level]
  (swap! *resources-page-state assoc :importance-filter level)
  (fetch-resources-fn))

(defn clear-all-resource-filters [app-state fetch-resources-fn]
  (swap! *resources-page-state assoc :filter-search "" :importance-filter nil)
  (fetch-resources-fn))

(defn reset-resources-page-view-state! []
  (swap! *resources-page-state assoc
         :expanded-resource nil
         :editing-resource nil))
