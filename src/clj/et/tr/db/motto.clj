(ns et.tr.db.motto
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [taoensso.telemere :as tel]
            [et.tr.db :as db]))

(defn add-motto
  ([ds user-id title] (add-motto ds user-id title "" "both" "both"))
  ([ds user-id title description scope]
   (add-motto ds user-id title description scope "both"))
  ([ds user-id title description scope time-window]
   (let [valid-scope (db/normalize-scope scope)
         valid-tw ((:time_window db/field-normalizers) time-window)
         result (jdbc/execute-one! (db/get-conn ds)
                  (sql/format {:insert-into :mottos
                               :values [{:title title
                                         :description (or description "")
                                         :scope valid-scope
                                         :time_window valid-tw
                                         :user_id user-id
                                         :modified_at [:raw "datetime('now')"]}]
                               :returning (conj db/motto-select-columns :user_id)})
                  db/jdbc-opts)]
     (tel/log! {:level :info :data {:motto-id (:id result) :user-id user-id}} "Motto added")
     result)))

(defn list-mottos
  ([ds user-id] (list-mottos ds user-id {}))
  ([ds user-id opts]
   (let [{:keys [search-term context strict]} opts
         user-where (db/user-id-where-clause user-id)
         search-clause (db/build-search-clause search-term [:title :description])
         scope-clause (db/build-scope-clause context strict)
         where-clause (into [:and user-where]
                            (filter some? [search-clause scope-clause]))]
     (jdbc/execute! (db/get-conn ds)
       (sql/format {:select db/motto-select-columns
                    :from [:mottos]
                    :where where-clause
                    :order-by [[:modified_at :desc]]})
       db/jdbc-opts))))

(defn motto-owned-by-user? [ds motto-id user-id]
  (some? (jdbc/execute-one! (db/get-conn ds)
           (sql/format {:select [:id]
                        :from [:mottos]
                        :where [:and [:= :id motto-id] (db/user-id-where-clause user-id)]})
           db/jdbc-opts)))

(defn get-motto [ds user-id motto-id]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:select db/motto-select-columns
                 :from [:mottos]
                 :where [:and [:= :id motto-id] (db/user-id-where-clause user-id)]})
    db/jdbc-opts))

(defn update-motto
  ([ds user-id motto-id fields] (update-motto ds user-id motto-id fields nil))
  ([ds user-id motto-id fields expected-modified-at]
   (let [field-names (keys fields)
         set-map (assoc fields :modified_at [:raw "datetime('now')"])
         return-cols (into [:id :created_at :modified_at] field-names)]
     (jdbc/execute-one! (db/get-conn ds)
       (sql/format {:update :mottos
                    :set set-map
                    :where (db/update-where motto-id user-id expected-modified-at)
                    :returning return-cols})
       db/jdbc-opts))))

(defn set-motto-field [ds user-id motto-id field value]
  (let [normalize-fn (get db/field-normalizers field identity)
        valid-value (normalize-fn value)]
    (jdbc/execute-one! (db/get-conn ds)
      (sql/format {:update :mottos
                   :set {field valid-value
                         :modified_at [:raw "datetime('now')"]}
                   :where [:and [:= :id motto-id] (db/user-id-where-clause user-id)]
                   :returning [:id field :modified_at]})
      db/jdbc-opts)))

(defn delete-motto [ds user-id motto-id]
  (when (motto-owned-by-user? ds motto-id user-id)
    (let [result (jdbc/execute-one! (db/get-conn ds)
                   (sql/format {:delete-from :mottos
                                :where [:= :id motto-id]}))]
      (tel/log! {:level :info :data {:motto-id motto-id :user-id user-id}} "Motto deleted")
      {:success (pos? (:next.jdbc/update-count result))})))
