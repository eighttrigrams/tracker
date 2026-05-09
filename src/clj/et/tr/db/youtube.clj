(ns et.tr.db.youtube
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [taoensso.telemere :as tel]
            [et.tr.db :as db]))

(def default-polling-minutes 60)

(def settings-select-columns [:user_id :enabled :polling_minutes :last_polled_at])
(def channel-select-columns [:id :channel_id :name :min_duration_minutes :enabled :added_at])

(defn get-settings
  "Return the user's YouTube settings row, or a defaulted map if none yet."
  [ds user-id]
  (or (jdbc/execute-one! (db/get-conn ds)
        (sql/format {:select settings-select-columns
                     :from [:youtube_settings]
                     :where [:= :user_id user-id]})
        db/jdbc-opts)
      {:user_id user-id
       :enabled 1
       :polling_minutes default-polling-minutes
       :last_polled_at nil}))

(defn upsert-settings
  "Insert or update the user's YouTube settings."
  [ds user-id {:keys [enabled polling-minutes]}]
  (let [conn (db/get-conn ds)
        existing (jdbc/execute-one! conn
                   (sql/format {:select [:user_id]
                                :from [:youtube_settings]
                                :where [:= :user_id user-id]})
                   db/jdbc-opts)
        enabled-val (if (boolean? enabled) (if enabled 1 0) enabled)
        set-map (cond-> {}
                  (some? enabled-val) (assoc :enabled enabled-val)
                  (some? polling-minutes) (assoc :polling_minutes polling-minutes))]
    (if existing
      (when (seq set-map)
        (jdbc/execute-one! conn
          (sql/format {:update :youtube_settings
                       :set set-map
                       :where [:= :user_id user-id]})))
      (jdbc/execute-one! conn
        (sql/format {:insert-into :youtube_settings
                     :values [(merge {:user_id user-id
                                      :enabled 1
                                      :polling_minutes default-polling-minutes}
                                     set-map)]})))
    (get-settings ds user-id)))

(defn set-last-polled!
  "Stamp the user's settings row with the current time as last_polled_at."
  [ds user-id]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:update :youtube_settings
                 :set {:last_polled_at [:raw "datetime('now')"]}
                 :where [:= :user_id user-id]})))

(defn list-channels
  [ds user-id]
  (jdbc/execute! (db/get-conn ds)
    (sql/format {:select channel-select-columns
                 :from [:youtube_channels]
                 :where [:= :user_id user-id]
                 :order-by [[:added_at :desc] [:id :desc]]})
    db/jdbc-opts))

(defn channel-owned-by-user? [ds user-id channel-row-id]
  (some? (jdbc/execute-one! (db/get-conn ds)
           (sql/format {:select [:id]
                        :from [:youtube_channels]
                        :where [:and [:= :id channel-row-id] [:= :user_id user-id]]})
           db/jdbc-opts)))

(defn add-channel
  [ds user-id {:keys [channel-id name min-duration-minutes enabled]}]
  (let [enabled-val (cond
                      (boolean? enabled) (if enabled 1 0)
                      (some? enabled) enabled
                      :else 1)
        result (jdbc/execute-one! (db/get-conn ds)
                 (sql/format {:insert-into :youtube_channels
                              :values [{:user_id user-id
                                        :channel_id channel-id
                                        :name name
                                        :min_duration_minutes min-duration-minutes
                                        :enabled enabled-val}]
                              :returning channel-select-columns})
                 db/jdbc-opts)]
    (tel/log! {:level :info :data {:channel-id channel-id :user-id user-id}}
              "YouTube channel added")
    result))

(defn update-channel
  [ds user-id channel-row-id fields]
  (when (channel-owned-by-user? ds user-id channel-row-id)
    (let [enabled (:enabled fields)
          set-map (cond-> {}
                    (contains? fields :name) (assoc :name (:name fields))
                    (contains? fields :min-duration-minutes)
                    (assoc :min_duration_minutes (:min-duration-minutes fields))
                    (some? enabled) (assoc :enabled (if (boolean? enabled)
                                                      (if enabled 1 0)
                                                      enabled)))]
      (when (seq set-map)
        (jdbc/execute-one! (db/get-conn ds)
          (sql/format {:update :youtube_channels
                       :set set-map
                       :where [:= :id channel-row-id]
                       :returning channel-select-columns})
          db/jdbc-opts)))))

(defn delete-channel [ds user-id channel-row-id]
  (when (channel-owned-by-user? ds user-id channel-row-id)
    (let [result (jdbc/execute-one! (db/get-conn ds)
                   (sql/format {:delete-from :youtube_channels
                                :where [:= :id channel-row-id]}))]
      (tel/log! {:level :info :data {:channel-row-id channel-row-id :user-id user-id}}
                "YouTube channel deleted")
      {:success (pos? (:next.jdbc/update-count result))})))

(defn video-notified? [ds user-id video-id]
  (some? (jdbc/execute-one! (db/get-conn ds)
           (sql/format {:select [1]
                        :from [:youtube_videos_notified]
                        :where [:and [:= :user_id user-id] [:= :video_id video-id]]})
           db/jdbc-opts)))

(defn mark-video-notified! [ds user-id video-id]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:insert-into :youtube_videos_notified
                 :values [{:user_id user-id :video_id video-id}]
                 :on-conflict []
                 :do-nothing true})))

(defn users-due-for-poll
  "Return user-ids whose YouTube source is enabled and whose polling cycle
  has elapsed (or who have never been polled). Restricted to users with
  has_mail = 1 since pulled videos go to the inbox."
  [ds]
  (mapv :user_id
    (jdbc/execute! (db/get-conn ds)
      (sql/format
       {:select [:s.user_id]
        :from [[:youtube_settings :s]]
        :join [[:users :u] [:= :u.id :s.user_id]]
        :where [:and
                [:= :s.enabled 1]
                [:= :u.has_mail 1]
                [:or
                 [:is :s.last_polled_at nil]
                 [:< :s.last_polled_at
                  [:raw "datetime('now', '-' || s.polling_minutes || ' minutes')"]]]]})
      db/jdbc-opts)))
