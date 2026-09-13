(ns et.tr.ui.state.users
  (:require [et.tr.ui.api :as api]
            [et.tr.ui.state.auth :as auth]))

(defn- replace-machine-user
  "Swap the row with id `(:id user)` inside :my-machine-users, leaving
  the rest in place. Used by every PUT path so renames/mail-only flips
  update locally without a re-fetch."
  [users user]
  (mapv (fn [u] (if (= (:id u) (:id user)) user u)) users))

(defn fetch-users [app-state auth-headers]
  (api/fetch-json-with-error "/api/users" (auth-headers)
    (fn [users]
      (swap! app-state assoc :users users))
    (fn [_]
      (swap! app-state assoc :users []))))

(defn fetch-available-users [app-state]
  (api/fetch-json-with-error "/api/auth/available-users" nil
    (fn [users]
      (swap! app-state assoc :available-users users))
    (fn [_]
      (swap! app-state assoc :available-users []))))

(defn add-user [app-state auth-headers username password machine-target-id mail-only? on-success]
  (let [body (cond-> {:username username :password password}
               machine-target-id (assoc :is_machine_user true
                                        :for_user_id machine-target-id
                                        :mail_only (boolean mail-only?)))]
    (api/post-json "/api/users" body (auth-headers)
      (fn [user]
        (swap! app-state update :users conj user)
        (when on-success (on-success)))
      (fn [resp]
        (swap! app-state assoc :error (get-in resp [:response :error] "Failed to add user"))))))

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

(defn switch-user
  "The dev switcher. `user` comes out of `/api/auth/available-users`, which
  answers names and roles and carries no `seal_prose`, so the flag is re-asked of
  `/api/auth/me` for whoever this now is — otherwise a switch away from the one
  person who seals would leave his key panel offered to the next one, and a
  switch towards him would never offer it at all."
  [app-state auth-headers initial-collection-state fetch-all-fn user]
  (swap! app-state merge
         initial-collection-state
         {:current-user user
          :show-user-switcher false
          :active-tab :today})
  (auth/apply-user-language user)
  (auth/refresh-current-user app-state auth-headers)
  (fetch-all-fn user))

(defn fetch-my-machine-users [app-state auth-headers]
  (api/fetch-json-with-error "/api/me/machine-users" (auth-headers)
    #(swap! app-state assoc :my-machine-users %)
    #(swap! app-state assoc :my-machine-users [])))

(defn add-my-machine-user [app-state auth-headers username password mail-only? on-success]
  (api/post-json "/api/me/machine-users"
    {:username username :password password :mail_only (boolean mail-only?)}
    (auth-headers)
    (fn [user]
      (swap! app-state update :my-machine-users (fnil conj []) user)
      (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error
             (get-in resp [:response :error] "Failed to add machine user")))))

(defn update-my-machine-user [app-state auth-headers user-id body on-success]
  (api/put-json (str "/api/me/machine-users/" user-id) body (auth-headers)
    (fn [user]
      (swap! app-state update :my-machine-users replace-machine-user user)
      (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error
             (get-in resp [:response :error] "Failed to update machine user")))))

(defn change-my-machine-user-password [app-state auth-headers user-id new-password on-success]
  (api/put-json (str "/api/me/machine-users/" user-id "/password")
    {:password new-password}
    (auth-headers)
    (fn [_] (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error
             (get-in resp [:response :error] "Failed to change password")))))

(defn delete-my-machine-user [app-state auth-headers user-id]
  (api/delete-simple (str "/api/me/machine-users/" user-id)
    (auth-headers)
    (fn [_]
      (swap! app-state update :my-machine-users
             (fn [users] (filterv #(not= (:id %) user-id) users))))
    (fn [resp]
      (swap! app-state assoc :error
             (get-in resp [:response :error] "Failed to delete machine user")))))
