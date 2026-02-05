(ns et.tr.ui.state.auth
  (:require [ajax.core :refer [GET POST]]
            [et.tr.i18n :as i18n]
            [et.tr.ui.api :as api]))

(defn save-auth-to-storage [token user]
  (when token
    (.setItem js/localStorage "auth-token" token))
  (when user
    (.setItem js/localStorage "auth-user" (js/JSON.stringify (clj->js user)))))

(defn clear-auth-from-storage []
  (.removeItem js/localStorage "auth-token")
  (.removeItem js/localStorage "auth-user"))

(defn load-auth-from-storage []
  (let [token (.getItem js/localStorage "auth-token")
        user-str (.getItem js/localStorage "auth-user")]
    {:token token
     :user (when user-str (js->clj (js/JSON.parse user-str) :keywordize-keys true))}))

(defn apply-user-language [user]
  (let [lang (or (:language user) "en")]
    (i18n/set-language! lang)))

(defn fetch-auth-required [app-state _auth-headers _initial-collection-state fetch-all-fn]
  (GET "/api/auth/required"
    {:response-format :json
     :keywords? true
     :handler (fn [resp]
                (swap! app-state assoc :auth-required? (:required resp))
                (if-not (:required resp)
                  (let [admin-user {:id nil :username "admin" :is_admin true :language "en"}]
                    (swap! app-state assoc
                           :logged-in? true
                           :current-user admin-user)
                    (apply-user-language admin-user)
                    (fetch-all-fn admin-user))
                  (let [{:keys [token user]} (load-auth-from-storage)]
                    (when (and token user)
                      (swap! app-state assoc
                             :logged-in? true
                             :token token
                             :current-user user)
                      (apply-user-language user)
                      (fetch-all-fn user)))))}))

(defn login [app-state fetch-messages-fn fetch-users-fn username password on-success]
  (POST "/api/auth/login"
    {:params {:username username :password password}
     :format :json
     :response-format :json
     :keywords? true
     :handler (fn [resp]
                (let [user (:user resp)
                      token (:token resp)]
                  (swap! app-state assoc
                         :logged-in? true
                         :token token
                         :current-user user
                         :error nil)
                  (save-auth-to-storage token user)
                  (apply-user-language user)
                  (when on-success (on-success))
                  (when (:is_admin user)
                    (fetch-messages-fn)
                    (fetch-users-fn))))
     :error-handler (fn [resp]
                      (swap! app-state assoc :error (get-in resp [:response :error] "Invalid credentials")))}))

(defn logout [app-state initial-collection-state]
  (clear-auth-from-storage)
  (swap! app-state merge
         initial-collection-state
         {:logged-in? false
          :token nil
          :current-user nil
          :users []}))

(defn update-user-language [app-state auth-headers language]
  (api/put-json "/api/user/language" {:language language} (auth-headers)
    (fn [_]
      (i18n/set-language! language)
      (swap! app-state update :current-user assoc :language language)
      (let [user (:current-user @app-state)
            token (:token @app-state)]
        (save-auth-to-storage token user)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update language")))))
