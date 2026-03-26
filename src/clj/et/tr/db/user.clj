(ns et.tr.db.user
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [buddy.hashers :as hashers]
            [taoensso.telemere :as tel]
            [et.tr.db :as db]))

(defn create-user [ds username password]
  (let [hash (hashers/derive password)
        result (jdbc/execute-one! (db/get-conn ds)
                 (sql/format {:insert-into :users
                              :values [{:username username :password_hash hash}]
                              :returning [:id :username :language :created_at]})
                 db/jdbc-opts)]
    (tel/log! {:level :info :data {:user-id (:id result) :username username}} "User created")
    result))

(defn get-user-by-username [ds username]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:select [:id :username :password_hash :language :has_mail :created_at]
                 :from [:users]
                 :where [:= :username username]})
    db/jdbc-opts))

(defn verify-user [ds username password]
  (when-let [user (get-user-by-username ds username)]
    (when (hashers/check password (:password_hash user))
      (dissoc user :password_hash))))

(defn list-users [ds]
  (jdbc/execute! (db/get-conn ds)
    (sql/format {:select [:id :username :language :has_mail :created_at]
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
