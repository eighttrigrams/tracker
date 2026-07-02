(ns et.tr.ui.state.mottos
  (:require [ajax.core :refer [GET]]
            [reagent.core :as r]
            [et.tr.ui.api :as api]
            [et.tr.ui.state.auth :as auth]))

(defonce *mottos-page-state (r/atom {:filter-search ""
                                     :editing-motto nil
                                     :confirm-delete-motto nil
                                     :fetch-request-id 0}))

(defn fetch-mottos [app-state auth-headers opts]
  (let [request-id (:fetch-request-id (swap! *mottos-page-state update :fetch-request-id inc))
        {:keys [search-term context strict]} opts
        url (cond-> "/api/mottos?"
              (seq search-term) (str "q=" (js/encodeURIComponent search-term) "&")
              context (str "context=" (name context) "&")
              strict (str "strict=true&"))]
    (GET url
      {:response-format :json
       :keywords? true
       :headers (auth-headers)
       :handler (fn [mottos]
                  (when (= request-id (:fetch-request-id @*mottos-page-state))
                    (swap! app-state assoc :mottos mottos)))
       :error-handler (fn [_]
                        (when (= request-id (:fetch-request-id @*mottos-page-state))
                          (swap! app-state assoc :mottos [])))})))

(defn add-motto [app-state auth-headers current-scope-fn title description on-success fetch-mottos-fn]
  (api/post-json "/api/mottos"
    {:title title
     :description (or description "")
     :scope (current-scope-fn)}
    (auth-headers)
    (fn [_]
      (fetch-mottos-fn)
      (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to add motto")))))

(defn update-motto [app-state auth-headers motto-id title description expected-modified-at on-success]
  (api/put-json (str "/api/mottos/" motto-id)
    (cond-> {:title title :description description}
      expected-modified-at (assoc :expected-modified-at expected-modified-at))
    (auth-headers)
    (fn [result]
      (swap! app-state update :mottos
             (fn [mottos]
               (mapv #(if (= (:id %) motto-id)
                        (merge % result)
                        %)
                     mottos)))
      (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update motto"))
      (when-let [current (and (= 409 (:status resp)) (get-in resp [:response :current]))]
        (swap! app-state update :mottos
               (fn [mottos]
                 (mapv #(if (= (:id %) motto-id) (merge % current) %) mottos)))))))

(defn delete-motto [app-state auth-headers motto-id]
  (api/delete-simple (str "/api/mottos/" motto-id)
    (auth-headers)
    (fn [_]
      (swap! app-state update :mottos
             (fn [mottos] (filterv #(not= (:id %) motto-id) mottos)))
      (swap! *mottos-page-state assoc :confirm-delete-motto nil))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to delete motto"))
      (swap! *mottos-page-state assoc :confirm-delete-motto nil))))

(defn set-motto-scope [app-state auth-headers motto-id scope]
  (api/put-json (str "/api/mottos/" motto-id "/scope")
    {:scope scope}
    (auth-headers)
    (fn [result]
      (swap! app-state update :mottos
             (fn [mottos]
               (mapv #(if (= (:id %) motto-id)
                        (assoc % :scope (:scope result))
                        %)
                     mottos))))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update scope")))))

(defn set-motto-time-window [app-state auth-headers motto-id time-window]
  (api/put-json (str "/api/mottos/" motto-id "/time-window")
    {:time_window time-window}
    (auth-headers)
    (fn [result]
      (swap! app-state update :mottos
             (fn [mottos]
               (mapv #(if (= (:id %) motto-id)
                        (assoc % :time_window (:time_window result))
                        %)
                     mottos))))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update time window")))))

(defn set-filter-search [fetch-mottos-fn search-term]
  (swap! *mottos-page-state assoc :filter-search search-term)
  (fetch-mottos-fn))

(defn set-editing-motto [id]
  (swap! *mottos-page-state assoc :editing-motto id))

(defn clear-editing-motto []
  (swap! *mottos-page-state assoc :editing-motto nil))

(defn set-confirm-delete-motto [motto]
  (swap! *mottos-page-state assoc :confirm-delete-motto motto))

(defn clear-confirm-delete-motto []
  (swap! *mottos-page-state assoc :confirm-delete-motto nil))

(defn update-screensaver-enabled [app-state auth-headers enabled]
  (api/put-json "/api/user/screensaver-enabled"
    {:screensaver_enabled (if enabled 1 0)}
    (auth-headers)
    (fn [_]
      (swap! app-state update :current-user assoc :screensaver_enabled (if enabled 1 0))
      (auth/save-auth-to-storage (:token @app-state) (:current-user @app-state)))
    (fn [resp]
      (swap! app-state assoc :error
             (get-in resp [:response :error] "Failed to update screensaver setting")))))

(defn update-screensaver-timeout [app-state auth-headers seconds]
  (api/put-json "/api/user/screensaver-timeout"
    {:screensaver_timeout_seconds seconds}
    (auth-headers)
    (fn [_]
      (swap! app-state update :current-user assoc :screensaver_timeout_seconds seconds)
      (auth/save-auth-to-storage (:token @app-state) (:current-user @app-state)))
    (fn [resp]
      (swap! app-state assoc :error
             (get-in resp [:response :error] "Failed to update screensaver timeout")))))
