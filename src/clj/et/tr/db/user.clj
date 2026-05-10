(ns et.tr.db.user
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [buddy.hashers :as hashers]
            [taoensso.telemere :as tel]
            [et.tr.db :as db]))

(defn create-user
  ([ds username password]
   (create-user ds username password {}))
  ([ds username password {:keys [is-machine-user for-user-id mail-only]}]
   (let [hash (hashers/derive password)
         row (cond-> {:username username :password_hash hash}
               is-machine-user (assoc :is_machine_user 1
                                      :for_user_id for-user-id
                                      :mail_only (if mail-only 1 0)))
         result (jdbc/execute-one! (db/get-conn ds)
                  (sql/format {:insert-into :users
                               :values [row]
                               :returning [:id :username :language :created_at :is_machine_user :for_user_id :mail_only]})
                  db/jdbc-opts)]
     (tel/log! {:level :info :data {:user-id (:id result) :username username
                                    :is-machine-user (boolean is-machine-user)
                                    :for-user-id for-user-id
                                    :mail-only (boolean mail-only)}} "User created")
     result)))

(defn get-user-by-username [ds username]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:select [:id :username :password_hash :language :has_mail :vim_keys :created_at
                          :is_machine_user :for_user_id :mail_only]
                 :from [:users]
                 :where [:= :username username]})
    db/jdbc-opts))

(defn get-user-by-id [ds user-id]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:select [:id :username :is_machine_user :for_user_id :has_mail :mail_only]
                 :from [:users]
                 :where [:= :id user-id]})
    db/jdbc-opts))

(defn verify-user [ds username password]
  (when-let [user (get-user-by-username ds username)]
    (when (hashers/check password (:password_hash user))
      (dissoc user :password_hash))))

(defn list-users [ds]
  (jdbc/execute! (db/get-conn ds)
    (sql/format {:select [:id :username :language :has_mail :vim_keys :created_at
                          :is_machine_user :for_user_id :mail_only]
                 :from [:users]
                 :where [:not= :username "admin"]
                 :order-by [[:created_at :asc]]})
    db/jdbc-opts))

(defn get-mail-user-id [ds]
  (:id (jdbc/execute-one! (db/get-conn ds)
         (sql/format {:select [:id]
                      :from [:users]
                      :where [:= :has_mail 1]})
         db/jdbc-opts)))

(defn delete-user [ds user-id]
  (let [conn (db/get-conn ds)]
    (jdbc/with-transaction [tx conn]
      (let [task-ids (mapv :id (jdbc/execute! tx
                                 (sql/format {:select [:id]
                                              :from [:tasks]
                                              :where [:= :user_id user-id]})
                                 db/jdbc-opts))]
        (when (seq task-ids)
          (jdbc/execute-one! tx
            (sql/format {:delete-from :task_categories
                         :where [:in :task_id task-ids]})))
        (jdbc/execute-one! tx (sql/format {:delete-from :tasks :where [:= :user_id user-id]}))
        (jdbc/execute-one! tx (sql/format {:delete-from :messages :where [:= :user_id user-id]}))
        (jdbc/execute-one! tx (sql/format {:delete-from :people :where [:= :user_id user-id]}))
        (jdbc/execute-one! tx (sql/format {:delete-from :places :where [:= :user_id user-id]}))
        (jdbc/execute-one! tx (sql/format {:delete-from :projects :where [:= :user_id user-id]}))
        (jdbc/execute-one! tx (sql/format {:delete-from :goals :where [:= :user_id user-id]}))
        (jdbc/execute-one! tx (sql/format {:delete-from :users
                                           :where [:and [:= :is_machine_user 1] [:= :for_user_id user-id]]}))
        (let [result (jdbc/execute-one! tx (sql/format {:delete-from :users :where [:= :id user-id]}))]
          (tel/log! {:level :info :data {:user-id user-id}} "User deleted")
          {:success (pos? (:next.jdbc/update-count result))})))))

(def valid-languages #{"en" "de" "pt"})

(defn get-user-language [ds user-id]
  (when user-id
    (:language (jdbc/execute-one! (db/get-conn ds)
                 (sql/format {:select [:language]
                              :from [:users]
                              :where [:= :id user-id]})
                 db/jdbc-opts))))

(defn set-user-language [ds user-id language]
  (when (and user-id (contains? valid-languages language))
    (jdbc/execute-one! (db/get-conn ds)
      (sql/format {:update :users
                   :set {:language language}
                   :where [:= :id user-id]
                   :returning [:id :language]})
      db/jdbc-opts)))

(defn set-vim-keys [ds user-id enabled]
  (when user-id
    (jdbc/execute-one! (db/get-conn ds)
      (sql/format {:update :users
                   :set {:vim_keys (if enabled 1 0)}
                   :where [:= :id user-id]
                   :returning [:id :vim_keys]})
      db/jdbc-opts)))

(defn list-machine-users-for-user
  "Rows for the parent user's machine users, ordered by creation time."
  [ds parent-user-id]
  (jdbc/execute! (db/get-conn ds)
    (sql/format {:select [:id :username :for_user_id :mail_only :created_at]
                 :from [:users]
                 :where [:and
                         [:= :is_machine_user 1]
                         [:= :for_user_id parent-user-id]]
                 :order-by [[:created_at :asc]]})
    db/jdbc-opts))

(defn update-username
  "Returns the updated row, or nil if no row matched. Throws on
  uniqueness violation (caller catches and surfaces 409)."
  [ds user-id new-username]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:update :users
                 :set {:username new-username}
                 :where [:= :id user-id]
                 :returning [:id :username :is_machine_user :for_user_id :mail_only]})
    db/jdbc-opts))

(defn update-mail-only [ds user-id mail-only?]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:update :users
                 :set {:mail_only (if mail-only? 1 0)}
                 :where [:= :id user-id]
                 :returning [:id :username :is_machine_user :for_user_id :mail_only]})
    db/jdbc-opts))

(defn update-password [ds user-id new-password]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:update :users
                 :set {:password_hash (hashers/derive new-password)}
                 :where [:= :id user-id]
                 :returning [:id :username]})
    db/jdbc-opts))
