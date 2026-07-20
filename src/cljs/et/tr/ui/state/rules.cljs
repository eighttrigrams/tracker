(ns et.tr.ui.state.rules
  (:require [ajax.core :refer [GET]]
            [et.tr.ui.api :as api]))

(defn fetch-rules [app-state auth-headers]
  (GET "/api/category-rules"
    {:response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler #(swap! app-state assoc :rules %)}))

(defn- fetch-all-of [app-state auth-headers endpoint state-key]
  (GET endpoint
    {:response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler #(swap! app-state assoc state-key %)}))

(defn fetch-rules-page [app-state auth-headers]
  (fetch-rules app-state auth-headers)
  (fetch-all-of app-state auth-headers "/api/people" :rules/people)
  (fetch-all-of app-state auth-headers "/api/places" :rules/places)
  (fetch-all-of app-state auth-headers "/api/projects" :rules/projects)
  (fetch-all-of app-state auth-headers "/api/goals" :rules/goals))

(defn add-rule [app-state auth-headers source-type source-id target-type target-id on-success]
  (api/post-json "/api/category-rules"
    {:source-type source-type :source-id source-id
     :target-type target-type :target-id target-id}
    (auth-headers)
    (fn [_]
      (fetch-rules app-state auth-headers)
      (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to add rule")))))

(defn delete-rule [app-state auth-headers rule-id]
  (api/delete-simple (str "/api/category-rules/" rule-id)
    (auth-headers)
    (fn [_] (fetch-rules app-state auth-headers))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to delete rule")))))

(defn resolve-filter-closure [auth-headers category-type category-id on-result]
  (api/post-json "/api/category-rules/resolve"
    {:category-type category-type :category-id category-id}
    (auth-headers)
    (fn [resp] (on-result (:categories resp)))
    (fn [_] (on-result nil))))
