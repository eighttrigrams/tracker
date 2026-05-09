(ns et.tr.db.atom-feed
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [taoensso.telemere :as tel]
            [et.tr.db :as db]))

(def default-polling-minutes 60)

(def settings-select-columns [:user_id :enabled :polling_minutes :last_polled_at])
(def feed-select-columns [:id :feed_url :name :enabled :added_at])

(defn get-settings
  [ds user-id]
  (or (jdbc/execute-one! (db/get-conn ds)
        (sql/format {:select settings-select-columns
                     :from [:atom_settings]
                     :where [:= :user_id user-id]})
        db/jdbc-opts)
      {:user_id user-id
       :enabled 1
       :polling_minutes default-polling-minutes
       :last_polled_at nil}))

(defn upsert-settings
  [ds user-id {:keys [enabled polling-minutes]}]
  (let [conn (db/get-conn ds)
        existing (jdbc/execute-one! conn
                   (sql/format {:select [:user_id]
                                :from [:atom_settings]
                                :where [:= :user_id user-id]})
                   db/jdbc-opts)
        enabled-val (if (boolean? enabled) (if enabled 1 0) enabled)
        set-map (cond-> {}
                  (some? enabled-val) (assoc :enabled enabled-val)
                  (some? polling-minutes) (assoc :polling_minutes polling-minutes))]
    (if existing
      (when (seq set-map)
        (jdbc/execute-one! conn
          (sql/format {:update :atom_settings
                       :set set-map
                       :where [:= :user_id user-id]})))
      (jdbc/execute-one! conn
        (sql/format {:insert-into :atom_settings
                     :values [(merge {:user_id user-id
                                      :enabled 1
                                      :polling_minutes default-polling-minutes}
                                     set-map)]})))
    (get-settings ds user-id)))

(defn ensure-settings! [ds user-id]
  (let [conn (db/get-conn ds)]
    (jdbc/execute-one! conn
      (sql/format {:insert-into :atom_settings
                   :values [{:user_id user-id
                             :enabled 1
                             :polling_minutes default-polling-minutes}]
                   :on-conflict []
                   :do-nothing true}))))

(defn set-last-polled! [ds user-id]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:update :atom_settings
                 :set {:last_polled_at [:raw "datetime('now')"]}
                 :where [:= :user_id user-id]})))

(defn list-feeds [ds user-id]
  (jdbc/execute! (db/get-conn ds)
    (sql/format {:select feed-select-columns
                 :from [:atom_feeds]
                 :where [:= :user_id user-id]
                 :order-by [[:added_at :desc] [:id :desc]]})
    db/jdbc-opts))

(defn feed-owned-by-user? [ds user-id feed-row-id]
  (some? (jdbc/execute-one! (db/get-conn ds)
           (sql/format {:select [:id]
                        :from [:atom_feeds]
                        :where [:and [:= :id feed-row-id] [:= :user_id user-id]]})
           db/jdbc-opts)))

(defn add-feed
  [ds user-id {:keys [feed-url name enabled]}]
  (let [enabled-val (cond
                      (boolean? enabled) (if enabled 1 0)
                      (some? enabled) enabled
                      :else 1)
        result (jdbc/execute-one! (db/get-conn ds)
                 (sql/format {:insert-into :atom_feeds
                              :values [{:user_id user-id
                                        :feed_url feed-url
                                        :name name
                                        :enabled enabled-val}]
                              :returning feed-select-columns})
                 db/jdbc-opts)]
    (tel/log! {:level :info :data {:feed-url feed-url :user-id user-id}}
              "Atom feed added")
    result))

(defn update-feed
  [ds user-id feed-row-id fields]
  (when (feed-owned-by-user? ds user-id feed-row-id)
    (let [enabled (:enabled fields)
          set-map (cond-> {}
                    (contains? fields :name) (assoc :name (:name fields))
                    (some? enabled) (assoc :enabled (if (boolean? enabled)
                                                      (if enabled 1 0)
                                                      enabled)))]
      (when (seq set-map)
        (jdbc/execute-one! (db/get-conn ds)
          (sql/format {:update :atom_feeds
                       :set set-map
                       :where [:= :id feed-row-id]
                       :returning feed-select-columns})
          db/jdbc-opts)))))

(defn delete-feed [ds user-id feed-row-id]
  (when (feed-owned-by-user? ds user-id feed-row-id)
    (let [result (jdbc/execute-one! (db/get-conn ds)
                   (sql/format {:delete-from :atom_feeds
                                :where [:= :id feed-row-id]}))]
      (tel/log! {:level :info :data {:feed-row-id feed-row-id :user-id user-id}}
                "Atom feed deleted")
      {:success (pos? (:next.jdbc/update-count result))})))

(defn entry-notified? [ds user-id entry-id]
  (some? (jdbc/execute-one! (db/get-conn ds)
           (sql/format {:select [1]
                        :from [:atom_entries_notified]
                        :where [:and [:= :user_id user-id] [:= :entry_id entry-id]]})
           db/jdbc-opts)))

(defn mark-entry-notified! [ds user-id entry-id]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:insert-into :atom_entries_notified
                 :values [{:user_id user-id :entry_id entry-id}]
                 :on-conflict []
                 :do-nothing true})))

(defn users-due-for-poll
  [ds]
  (mapv :user_id
    (jdbc/execute! (db/get-conn ds)
      (sql/format
       {:select [:s.user_id]
        :from [[:atom_settings :s]]
        :join [[:users :u] [:= :u.id :s.user_id]]
        :where [:and
                [:= :s.enabled 1]
                [:= :u.has_mail 1]
                [:or
                 [:is :s.last_polled_at nil]
                 [:< :s.last_polled_at
                  [:raw "datetime('now', '-' || s.polling_minutes || ' minutes')"]]]]})
      db/jdbc-opts)))
