(ns et.tr.ui.state.categories
  (:require [ajax.core :refer [GET]]
            [et.tr.ui.api :as api]))

(defn- fetch-collection [auth-headers endpoint state-key app-state]
  (GET endpoint
    {:response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler #(swap! app-state assoc state-key %)}))

(defn fetch-people [app-state auth-headers]
  (fetch-collection auth-headers "/api/people" :people app-state))

(defn fetch-places [app-state auth-headers]
  (fetch-collection auth-headers "/api/places" :places app-state))

(defn fetch-projects [app-state auth-headers]
  (fetch-collection auth-headers "/api/projects" :projects app-state))

(defn fetch-goals [app-state auth-headers]
  (fetch-collection auth-headers "/api/goals" :goals app-state))

(defn- add-category-entity [app-state auth-headers endpoint state-key error-msg name on-success]
  (api/post-json endpoint {:name name} (auth-headers)
    (fn [entity]
      (swap! app-state update state-key conj entity)
      (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] error-msg)))))

(defn add-person [app-state auth-headers name on-success]
  (add-category-entity app-state auth-headers "/api/people" :people "Failed to add person" name on-success))

(defn add-place [app-state auth-headers name on-success]
  (add-category-entity app-state auth-headers "/api/places" :places "Failed to add place" name on-success))

(defn add-project [app-state auth-headers name on-success]
  (add-category-entity app-state auth-headers "/api/projects" :projects "Failed to add project" name on-success))

(defn add-goal [app-state auth-headers name on-success]
  (add-category-entity app-state auth-headers "/api/goals" :goals "Failed to add goal" name on-success))

(defn- update-category-entity [app-state auth-headers fetch-tasks-fn endpoint state-key error-msg id name description tags badge-title on-success]
  (api/put-json (str endpoint id)
    {:name name :description description :tags tags :badge-title badge-title}
    (auth-headers)
    (fn [updated]
      (swap! app-state update state-key
             (fn [items] (mapv #(if (= (:id %) id) updated %) items)))
      (fetch-tasks-fn)
      (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] error-msg)))))

(defn update-person [app-state auth-headers fetch-tasks-fn id name description tags badge-title on-success]
  (update-category-entity app-state auth-headers fetch-tasks-fn "/api/people/" :people "Failed to update person" id name description tags badge-title on-success))

(defn update-place [app-state auth-headers fetch-tasks-fn id name description tags badge-title on-success]
  (update-category-entity app-state auth-headers fetch-tasks-fn "/api/places/" :places "Failed to update place" id name description tags badge-title on-success))

(defn update-project [app-state auth-headers fetch-tasks-fn id name description tags badge-title on-success]
  (update-category-entity app-state auth-headers fetch-tasks-fn "/api/projects/" :projects "Failed to update project" id name description tags badge-title on-success))

(defn update-goal [app-state auth-headers fetch-tasks-fn id name description tags badge-title on-success]
  (update-category-entity app-state auth-headers fetch-tasks-fn "/api/goals/" :goals "Failed to update goal" id name description tags badge-title on-success))

(defn set-confirm-delete-category [app-state category-type category]
  (swap! app-state assoc :confirm-delete-category {:type category-type :category category}))

(defn clear-confirm-delete-category [app-state]
  (swap! app-state assoc :confirm-delete-category nil))

(defn- delete-category-entity [app-state auth-headers fetch-tasks-fn endpoint state-key error-msg id]
  (api/delete-simple (str endpoint id)
    (auth-headers)
    (fn [_]
      (swap! app-state update state-key
             (fn [items] (filterv #(not= (:id %) id) items)))
      (fetch-tasks-fn)
      (clear-confirm-delete-category app-state))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] error-msg))
      (clear-confirm-delete-category app-state))))

(defn delete-person [app-state auth-headers fetch-tasks-fn id]
  (delete-category-entity app-state auth-headers fetch-tasks-fn "/api/people/" :people "Failed to delete person" id))

(defn delete-place [app-state auth-headers fetch-tasks-fn id]
  (delete-category-entity app-state auth-headers fetch-tasks-fn "/api/places/" :places "Failed to delete place" id))

(defn delete-project [app-state auth-headers fetch-tasks-fn id]
  (delete-category-entity app-state auth-headers fetch-tasks-fn "/api/projects/" :projects "Failed to delete project" id))

(defn delete-goal [app-state auth-headers fetch-tasks-fn id]
  (delete-category-entity app-state auth-headers fetch-tasks-fn "/api/goals/" :goals "Failed to delete goal" id))

(defn set-editing-category [app-state category-type id]
  (swap! app-state assoc :category-page/editing {:type category-type :id id}))

(defn clear-editing-category [app-state]
  (swap! app-state assoc :category-page/editing nil))

(defn set-drag-category [app-state category-type category-id]
  (swap! app-state assoc :drag-category {:type category-type :id category-id}))

(defn set-drag-over-category [app-state category-type category-id]
  (swap! app-state assoc :drag-over-category {:type category-type :id category-id}))

(defn clear-category-drag-state [app-state]
  (swap! app-state assoc :drag-category nil :drag-over-category nil))

(defn reorder-category [app-state auth-headers fetch-people-fn fetch-places-fn fetch-projects-fn fetch-goals-fn
                        category-type category-id target-category-id position]
  (let [endpoint (case category-type
                   :people "/api/people/"
                   :places "/api/places/"
                   :projects "/api/projects/"
                   :goals "/api/goals/")
        fetch-fn (case category-type
                   :people fetch-people-fn
                   :places fetch-places-fn
                   :projects fetch-projects-fn
                   :goals fetch-goals-fn)]
    (api/post-json (str endpoint category-id "/reorder")
      {:target-category-id target-category-id :position position}
      (auth-headers)
      (fn [_]
        (clear-category-drag-state app-state)
        (fetch-fn))
      (fn [resp]
        (clear-category-drag-state app-state)
        (swap! app-state assoc :error (get-in resp [:response :error] "Failed to reorder"))))))
