(ns et.tr.ui.state.sources
  (:require [ajax.core :refer [GET]]
            [reagent.core :as r]
            [et.tr.ui.api :as api]))

(defonce *sources-page-state
  (r/atom {:mode false
           :loaded? false
           :error nil

           ;; YouTube
           :settings nil
           :channels []
           :add-channel-id ""
           :add-channel-name ""
           :add-channel-min ""

           ;; Podcasts
           :podcast-settings nil
           :podcast-feeds []
           :add-podcast-url ""
           :add-podcast-name ""

           ;; Atom feeds
           :atom-settings nil
           :atom-feeds []
           :add-atom-url ""
           :add-atom-name ""}))

(defn sources-mode? [] (:mode @*sources-page-state))

(defn- fetch-json [path auth-headers k]
  (GET path
    {:response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler #(swap! *sources-page-state assoc k %)
     :error-handler #(swap! *sources-page-state assoc :error
                            (str "Failed to load " (name k)))}))

(defn fetch-all [auth-headers]
  (fetch-json "/api/sources/youtube/settings" auth-headers :settings)
  (fetch-json "/api/sources/podcast/settings" auth-headers :podcast-settings)
  (fetch-json "/api/sources/atom/settings"    auth-headers :atom-settings)
  (GET "/api/sources/youtube/channels"
    {:response-format :json :keywords? true :headers (auth-headers)
     :handler #(swap! *sources-page-state assoc :channels (vec %))
     :error-handler #(swap! *sources-page-state assoc :error "Failed to load channels")})
  (GET "/api/sources/podcast/feeds"
    {:response-format :json :keywords? true :headers (auth-headers)
     :handler #(swap! *sources-page-state assoc :podcast-feeds (vec %))
     :error-handler #(swap! *sources-page-state assoc :error "Failed to load podcast feeds")})
  (GET "/api/sources/atom/feeds"
    {:response-format :json :keywords? true :headers (auth-headers)
     :handler #(swap! *sources-page-state assoc :atom-feeds (vec %) :loaded? true)
     :error-handler #(swap! *sources-page-state assoc :error "Failed to load atom feeds"
                                                       :loaded? true)}))

(defn toggle-mode [auth-headers]
  (let [new-mode (not (:mode @*sources-page-state))]
    (swap! *sources-page-state assoc :mode new-mode)
    (when new-mode (fetch-all auth-headers))))

;; ── YouTube ────────────────────────────────────────────────────────────

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

(defn set-channel-scope [auth-headers channel-id scope]
  (update-channel auth-headers channel-id {:scope scope}))

(defn set-channel-importance [auth-headers channel-id importance]
  (update-channel auth-headers channel-id {:importance importance}))

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

;; ── Generic feed-source helpers (podcasts, atom) ───────────────────────

(defn- set-feed-error [resp default-msg]
  (swap! *sources-page-state assoc :error
         (get-in resp [:response :error] default-msg)))

(defn- update-feed-settings*
  [auth-headers path settings-key fields]
  (api/put-json path fields (auth-headers)
    (fn [updated] (swap! *sources-page-state assoc settings-key updated :error nil))
    (fn [resp] (set-feed-error resp "Failed to update settings"))))

(defn- add-feed*
  [auth-headers path feeds-key url-key name-key]
  (let [s @*sources-page-state
        url (get s url-key)
        name-val (get s name-key)
        params (cond-> {:feed_url url}
                 (seq name-val) (assoc :name name-val))]
    (api/post-json path params (auth-headers)
      (fn [created]
        (swap! *sources-page-state
               (fn [st] (-> st
                            (update feeds-key #(into [created] %))
                            (assoc url-key "" name-key "" :error nil)))))
      (fn [resp] (set-feed-error resp "Failed to add feed")))))

(defn- update-feed*
  [auth-headers path feeds-key feed-id fields]
  (api/put-json (str path "/" feed-id) fields (auth-headers)
    (fn [updated]
      (swap! *sources-page-state update feeds-key
             (fn [fs] (mapv #(if (= (:id %) feed-id) updated %) fs)))
      (swap! *sources-page-state assoc :error nil))
    (fn [resp] (set-feed-error resp "Failed to update feed"))))

(defn- delete-feed*
  [auth-headers path feeds-key feed-id]
  (api/delete-simple (str path "/" feed-id) (auth-headers)
    (fn [_]
      (swap! *sources-page-state update feeds-key
             (fn [fs] (filterv #(not= (:id %) feed-id) fs))))
    (fn [resp] (set-feed-error resp "Failed to delete feed"))))

;; ── Podcasts ───────────────────────────────────────────────────────────

(defn set-podcast-enabled [auth-headers enabled]
  (update-feed-settings* auth-headers "/api/sources/podcast/settings"
                         :podcast-settings {:enabled enabled}))

(defn set-podcast-polling-minutes [auth-headers minutes]
  (update-feed-settings* auth-headers "/api/sources/podcast/settings"
                         :podcast-settings {:polling_minutes minutes}))

(defn add-podcast-feed [auth-headers]
  (add-feed* auth-headers "/api/sources/podcast/feeds"
             :podcast-feeds :add-podcast-url :add-podcast-name))

(defn set-podcast-feed-enabled [auth-headers feed-id enabled]
  (update-feed* auth-headers "/api/sources/podcast/feeds"
                :podcast-feeds feed-id {:enabled enabled}))

(defn set-podcast-feed-name [auth-headers feed-id name-val]
  (update-feed* auth-headers "/api/sources/podcast/feeds"
                :podcast-feeds feed-id {:name name-val}))

(defn delete-podcast-feed [auth-headers feed-id]
  (delete-feed* auth-headers "/api/sources/podcast/feeds"
                :podcast-feeds feed-id))

;; ── Atom ───────────────────────────────────────────────────────────────

(defn set-atom-enabled [auth-headers enabled]
  (update-feed-settings* auth-headers "/api/sources/atom/settings"
                         :atom-settings {:enabled enabled}))

(defn set-atom-polling-minutes [auth-headers minutes]
  (update-feed-settings* auth-headers "/api/sources/atom/settings"
                         :atom-settings {:polling_minutes minutes}))

(defn add-atom-feed [auth-headers]
  (add-feed* auth-headers "/api/sources/atom/feeds"
             :atom-feeds :add-atom-url :add-atom-name))

(defn set-atom-feed-enabled [auth-headers feed-id enabled]
  (update-feed* auth-headers "/api/sources/atom/feeds"
                :atom-feeds feed-id {:enabled enabled}))

(defn set-atom-feed-name [auth-headers feed-id name-val]
  (update-feed* auth-headers "/api/sources/atom/feeds"
                :atom-feeds feed-id {:name name-val}))

(defn delete-atom-feed [auth-headers feed-id]
  (delete-feed* auth-headers "/api/sources/atom/feeds"
                :atom-feeds feed-id))
