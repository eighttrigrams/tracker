(ns et.tr.ui.state
  (:require [reagent.core :as r]
            [ajax.core :refer [GET POST]]))

(defonce app-state (r/atom {:items []
                            :auth-required? nil
                            :logged-in? false
                            :token nil
                            :error nil}))

(defn auth-headers []
  (when-let [token (:token @app-state)]
    {"Authorization" (str "Bearer " token)}))

(defn fetch-auth-required []
  (GET "/api/auth/required"
    {:response-format :json
     :keywords? true
     :handler (fn [resp]
                (swap! app-state assoc :auth-required? (:required resp))
                (when-not (:required resp)
                  (swap! app-state assoc :logged-in? true)))}))

(defn login [password on-success]
  (POST "/api/auth/login"
    {:params {:email "admin" :password password}
     :format :json
     :response-format :json
     :keywords? true
     :handler (fn [resp]
                (swap! app-state assoc
                       :logged-in? true
                       :token (:token resp)
                       :error nil)
                (when on-success (on-success)))
     :error-handler (fn [_]
                      (swap! app-state assoc :error "Invalid password"))}))

(defn fetch-items []
  (GET "/api/items"
    {:response-format :json
     :keywords? true
     :handler (fn [items]
                (swap! app-state assoc :items items))}))

(defn add-item [title on-success]
  (POST "/api/items"
    {:params {:title title}
     :format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [item]
                (swap! app-state update :items #(cons item %))
                (when on-success (on-success)))
     :error-handler (fn [resp]
                      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to add item")))}))
