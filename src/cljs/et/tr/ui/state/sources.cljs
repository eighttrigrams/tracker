(ns et.tr.ui.state.sources
  (:require [ajax.core :refer [GET]]
            [reagent.core :as r]
            [et.tr.ui.api :as api]))

(defonce *sources-page-state
  (r/atom {:mode false
           :settings nil
           :channels []
           :loaded? false
           :add-channel-id ""
           :add-channel-name ""
           :add-channel-min ""
           :error nil}))

(defn sources-mode? [] (:mode @*sources-page-state))

(defn fetch-all [auth-headers]
  (GET "/api/sources/youtube/settings"
    {:response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler #(swap! *sources-page-state assoc :settings %)
     :error-handler #(swap! *sources-page-state assoc :error "Failed to load YouTube settings")})
  (GET "/api/sources/youtube/channels"
    {:response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler #(swap! *sources-page-state assoc :channels (vec %) :loaded? true)
     :error-handler #(swap! *sources-page-state assoc :error "Failed to load channels"
                                                       :loaded? true)}))

(defn toggle-mode [auth-headers]
  (let [new-mode (not (:mode @*sources-page-state))]
    (swap! *sources-page-state assoc :mode new-mode)
    (when new-mode (fetch-all auth-headers))))

(defn update-settings [auth-headers fields]
  (api/put-json "/api/sources/youtube/settings"
    fields
    (auth-headers)
    (fn [updated] (swap! *sources-page-state assoc :settings updated :error nil))
    (fn [resp]
      (swap! *sources-page-state assoc :error
             (get-in resp [:response :error] "Failed to update settings")))))

(defn set-enabled [auth-headers enabled]
  (update-settings auth-headers {:enabled enabled}))

(defn set-polling-minutes [auth-headers minutes]
  (update-settings auth-headers {:polling_minutes minutes}))

(defn add-channel [auth-headers]
  (let [{:keys [add-channel-id add-channel-name add-channel-min]} @*sources-page-state
        params (cond-> {:channel_id add-channel-id}
                 (seq add-channel-name) (assoc :name add-channel-name)
                 (seq add-channel-min) (assoc :min_duration_minutes add-channel-min))]
    (api/post-json "/api/sources/youtube/channels"
      params
      (auth-headers)
      (fn [created]
        (swap! *sources-page-state
               (fn [s] (-> s
                           (update :channels #(into [created] %))
                           (assoc :add-channel-id "" :add-channel-name "" :add-channel-min ""
                                  :error nil)))))
      (fn [resp]
        (swap! *sources-page-state assoc :error
               (get-in resp [:response :error] "Failed to add channel"))))))

(defn update-channel [auth-headers channel-id fields]
  (api/put-json (str "/api/sources/youtube/channels/" channel-id)
    fields
    (auth-headers)
    (fn [updated]
      (swap! *sources-page-state update :channels
             (fn [chs] (mapv #(if (= (:id %) channel-id) updated %) chs)))
      (swap! *sources-page-state assoc :error nil))
    (fn [resp]
      (swap! *sources-page-state assoc :error
             (get-in resp [:response :error] "Failed to update channel")))))

(defn set-channel-enabled [auth-headers channel-id enabled]
  (update-channel auth-headers channel-id {:enabled enabled}))

(defn set-channel-name [auth-headers channel-id name-val]
  (update-channel auth-headers channel-id {:name name-val}))

(defn set-channel-min-minutes [auth-headers channel-id minutes]
  (update-channel auth-headers channel-id {:min_duration_minutes minutes}))

(defn delete-channel [auth-headers channel-id]
  (api/delete-simple (str "/api/sources/youtube/channels/" channel-id)
    (auth-headers)
    (fn [_]
      (swap! *sources-page-state update :channels
             (fn [chs] (filterv #(not= (:id %) channel-id) chs))))
    (fn [resp]
      (swap! *sources-page-state assoc :error
             (get-in resp [:response :error] "Failed to delete channel")))))

(defn set-form-field [k v]
  (swap! *sources-page-state assoc k v))
