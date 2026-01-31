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

(defn add-person [app-state auth-headers name on-success]
  (api/post-json "/api/people" {:name name} (auth-headers)
    (fn [person]
      (swap! app-state update :people conj person)
      (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to add person")))))

(defn add-place [app-state auth-headers name on-success]
  (api/post-json "/api/places" {:name name} (auth-headers)
    (fn [place]
      (swap! app-state update :places conj place)
      (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to add place")))))

(defn add-project [app-state auth-headers name on-success]
  (api/post-json "/api/projects" {:name name} (auth-headers)
    (fn [project]
      (swap! app-state update :projects conj project)
      (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to add project")))))

(defn add-goal [app-state auth-headers name on-success]
  (api/post-json "/api/goals" {:name name} (auth-headers)
    (fn [goal]
      (swap! app-state update :goals conj goal)
      (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to add goal")))))

(defn update-person [app-state auth-headers fetch-tasks-fn id name description on-success]
  (api/put-json (str "/api/people/" id)
    {:name name :description description}
    (auth-headers)
    (fn [updated]
      (swap! app-state update :people
             (fn [items] (mapv #(if (= (:id %) id) updated %) items)))
      (fetch-tasks-fn)
      (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update person")))))

(defn update-place [app-state auth-headers fetch-tasks-fn id name description on-success]
  (api/put-json (str "/api/places/" id)
    {:name name :description description}
    (auth-headers)
    (fn [updated]
      (swap! app-state update :places
             (fn [items] (mapv #(if (= (:id %) id) updated %) items)))
      (fetch-tasks-fn)
      (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update place")))))

(defn update-project [app-state auth-headers fetch-tasks-fn id name description on-success]
  (api/put-json (str "/api/projects/" id)
    {:name name :description description}
    (auth-headers)
    (fn [updated]
      (swap! app-state update :projects
             (fn [items] (mapv #(if (= (:id %) id) updated %) items)))
      (fetch-tasks-fn)
      (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update project")))))

(defn update-goal [app-state auth-headers fetch-tasks-fn id name description on-success]
  (api/put-json (str "/api/goals/" id)
    {:name name :description description}
    (auth-headers)
    (fn [updated]
      (swap! app-state update :goals
             (fn [items] (mapv #(if (= (:id %) id) updated %) items)))
      (fetch-tasks-fn)
      (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update goal")))))

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
