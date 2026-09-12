(ns et.tr.ui.state.rules
  (:require [et.tr.ui.api :as api]
            [et.tr.ui.constants :as constants]))

(defn fetch-rules [app-state auth-headers]
  (api/fetch-json "/api/category-rules" (auth-headers)
    #(swap! app-state assoc :rules %)))

(defn- fetch-all-of [app-state auth-headers endpoint state-key]
  (api/fetch-json endpoint (auth-headers)
    #(swap! app-state assoc state-key %)))

(defn fetch-rules-page [app-state auth-headers]
  (fetch-rules app-state auth-headers)
  ;; Unscoped lists, one per Category Group: a rule may name any category, so
  ;; the pickers must offer every group.
  (doseq [group-key constants/category-key-order]
    (fetch-all-of app-state auth-headers
                  (constants/category-key->endpoint group-key)
                  (keyword "rules" (name group-key)))))

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
