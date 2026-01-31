(ns et.tr.ui.state.users
  (:require [ajax.core :refer [GET]]
            [et.tr.ui.api :as api]
            [et.tr.ui.state.auth :as auth]))

(defn fetch-users [app-state auth-headers]
  (GET "/api/users"
    {:response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [users]
                (swap! app-state assoc :users users))
     :error-handler (fn [_]
                      (swap! app-state assoc :users []))}))

(defn fetch-available-users [app-state]
  (GET "/api/auth/available-users"
    {:response-format :json
     :keywords? true
     :handler (fn [users]
                (swap! app-state assoc :available-users users))
     :error-handler (fn [_]
                      (swap! app-state assoc :available-users []))}))

(defn add-user [app-state auth-headers username password on-success]
  (api/post-json "/api/users" {:username username :password password} (auth-headers)
    (fn [user]
      (swap! app-state update :users conj user)
      (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to add user")))))

(defn set-confirm-delete-user [app-state user]
  (swap! app-state assoc :confirm-delete-user user))

(defn clear-confirm-delete-user [app-state]
  (swap! app-state assoc :confirm-delete-user nil))

(defn delete-user [app-state auth-headers user-id]
  (api/delete-simple (str "/api/users/" user-id)
    (auth-headers)
    (fn [_]
      (swap! app-state update :users
             (fn [users] (filterv #(not= (:id %) user-id) users)))
      (clear-confirm-delete-user app-state))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to delete user"))
      (clear-confirm-delete-user app-state))))

(defn toggle-user-switcher [app-state]
  (swap! app-state update :show-user-switcher not))

(defn close-user-switcher [app-state]
  (swap! app-state assoc :show-user-switcher false))

(defn switch-user [app-state initial-collection-state fetch-all-fn user]
  (swap! app-state merge
         initial-collection-state
         {:current-user user
          :show-user-switcher false
          :active-tab :today})
  (auth/apply-user-language user)
  (fetch-all-fn user))
