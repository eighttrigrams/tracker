(ns et.tr.db.task
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [taoensso.telemere :as tel]
            [et.tr.db :as db]
            [et.tr.db.relation :as relation]))

(defn add-task
  ([ds user-id title] (add-task ds user-id title "both"))
  ([ds user-id title scope]
   (let [conn (db/get-conn ds)
         valid-scope (db/normalize-scope scope)
         min-order (or (:min_order (jdbc/execute-one! conn
                                     (sql/format {:select [[[:min :sort_order] :min_order]]
                                                  :from [:tasks]
                                                  :where (db/user-id-where-clause user-id)})
                                     db/jdbc-opts))
                       1.0)
         new-order (- min-order 1.0)
         result (jdbc/execute-one! conn
                  (sql/format {:insert-into :tasks
                               :values [{:title title
                                         :sort_order new-order
                                         :user_id user-id
                                         :modified_at [:raw "datetime('now')"]
                                         :scope valid-scope}]
                               :returning (conj db/task-select-columns :user_id)})
                  db/jdbc-opts)]
     (tel/log! {:level :info :data {:task-id (:id result) :user-id user-id}} "Task added")
     result)))

(defn- build-category-clauses [categories]
  (let [people-clause (db/build-category-subquery "person" (:people categories))
        places-clause (db/build-category-subquery "place" (:places categories))
        projects-clause (db/build-category-subquery "project" (:projects categories))
        goals-clause (db/build-category-subquery "goal" (:goals categories))]
    (filterv some? [people-clause places-clause projects-clause goals-clause])))

(defn list-tasks
  ([ds user-id] (list-tasks ds user-id :recent))
  ([ds user-id sort-mode] (list-tasks ds user-id sort-mode nil))
  ([ds user-id sort-mode opts]
   (let [opts (if (string? opts) {:search-term opts} opts)
         {:keys [search-term importance context strict categories excluded-places excluded-projects]} opts
         conn (db/get-conn ds)
         user-where (db/user-id-where-clause user-id)
         base-where (case sort-mode
                      :due-date [:and user-where [:not= :due_date nil] [:= :done 0]]
                      :done [:and user-where [:= :done 1]]
                      :today [:and user-where [:= :done 0]
                              [:or [:not= :due_date nil]
                                   [:in :urgency ["urgent" "superurgent"]]
                                   [:= :today 1]]]
                      [:and user-where [:= :done 0]])
         search-clause (db/build-search-clause search-term)
         importance-clause (db/build-importance-clause importance)
         scope-clause (db/build-scope-clause context strict)
         category-clauses (build-category-clauses categories)
         exclusion-clauses (filterv some? [(db/build-exclusion-subquery "place" excluded-places)
                                           (db/build-exclusion-subquery "project" excluded-projects)])
         where-clause (into [:and base-where]
                            (concat (filter some? [search-clause importance-clause scope-clause])
                                    category-clauses
                                    exclusion-clauses))
         order-by (case sort-mode
                    :manual [[:sort_order :asc] [:created_at :desc]]
                    :due-date [[:due_date :asc]
                               [[:case [:not= :due_time nil] 1 :else 0] :desc]
                               [:due_time :asc]]
                    :done [[:modified_at :desc]]
                    :today [[:due_date :asc]
                            [[:case [:not= :due_time nil] 1 :else 0] :desc]
                            [:due_time :asc]]
                    [[:modified_at :desc]])
         tasks (jdbc/execute! conn
                 (sql/format {:select db/task-select-columns
                              :from [:tasks]
                              :where where-clause
                              :order-by order-by})
                 db/jdbc-opts)
         task-ids (mapv :id tasks)
         categories (when (seq task-ids)
                      (jdbc/execute! conn
                        (sql/format {:select [:task_id :category_type :category_id]
                                     :from [:task_categories]
                                     :where [:in :task_id task-ids]})
                        db/jdbc-opts))
         {:keys [people-by-id places-by-id projects-by-id goals-by-id]} (db/fetch-category-lookups conn user-where)
         categories-by-task (group-by :task_id categories)
         tasks-with-categories (db/associate-categories-with-tasks tasks categories-by-task people-by-id places-by-id projects-by-id goals-by-id)]
     (relation/associate-relations-with-items tasks-with-categories "tsk" conn))))

(defn get-task [ds user-id task-id]
  (let [conn (db/get-conn ds)
        user-where (db/user-id-where-clause user-id)
        task (jdbc/execute-one! conn
               (sql/format {:select db/task-select-columns
                            :from [:tasks]
                            :where [:and [:= :id task-id] user-where]})
               db/jdbc-opts)]
    (when task
      (let [categories (jdbc/execute! conn
                         (sql/format {:select [:task_id :category_type :category_id]
                                      :from [:task_categories]
                                      :where [:= :task_id task-id]})
                         db/jdbc-opts)
            {:keys [people-by-id places-by-id projects-by-id goals-by-id]} (db/fetch-category-lookups conn user-where)
            categories-by-task (group-by :task_id categories)]
        (first (db/associate-categories-with-tasks [task] categories-by-task people-by-id places-by-id projects-by-id goals-by-id))))))

(defn task-owned-by-user? [ds task-id user-id]
  (some? (jdbc/execute-one! (db/get-conn ds)
           (sql/format {:select [:id]
                        :from [:tasks]
                        :where [:and [:= :id task-id] (db/user-id-where-clause user-id)]})
           db/jdbc-opts)))

(defn categorize-task [ds user-id task-id category-type category-id]
  (db/validate-category-type! category-type)
  (when (and (task-owned-by-user? ds task-id user-id)
             (db/category-owned-by-user? ds category-type category-id user-id))
    (let [conn (db/get-conn ds)]
      (jdbc/with-transaction [tx conn]
        (jdbc/execute-one! tx
          (sql/format {:insert-into :task_categories
                       :values [{:task_id task-id
                                 :category_type category-type
                                 :category_id category-id}]
                       :on-conflict []
                       :do-nothing true}))
        (jdbc/execute-one! tx
          (sql/format {:update :tasks
                       :set {:modified_at [:raw "datetime('now')"]}
                       :where [:= :id task-id]}))))))

(defn uncategorize-task [ds user-id task-id category-type category-id]
  (db/validate-category-type! category-type)
  (when (and (task-owned-by-user? ds task-id user-id)
             (db/category-owned-by-user? ds category-type category-id user-id))
    (let [conn (db/get-conn ds)]
      (jdbc/with-transaction [tx conn]
        (jdbc/execute-one! tx
          (sql/format {:delete-from :task_categories
                       :where [:and
                               [:= :task_id task-id]
                               [:= :category_type category-type]
                               [:= :category_id category-id]]}))
        (jdbc/execute-one! tx
          (sql/format {:update :tasks
                       :set {:modified_at [:raw "datetime('now')"]}
                       :where [:= :id task-id]}))))))

(defn update-task [ds user-id task-id fields]
  (let [field-names (keys fields)
        set-map (assoc fields :modified_at [:raw "datetime('now')"])
        return-cols (into [:id :created_at :modified_at] field-names)]
    (jdbc/execute-one! (db/get-conn ds)
      (sql/format {:update :tasks
                   :set set-map
                   :where [:and [:= :id task-id] (db/user-id-where-clause user-id)]
                   :returning return-cols})
      db/jdbc-opts)))

(defn get-task-sort-order [ds user-id task-id]
  (:sort_order (jdbc/execute-one! (db/get-conn ds)
                 (sql/format {:select [:sort_order]
                              :from [:tasks]
                              :where [:and [:= :id task-id] (db/user-id-where-clause user-id)]})
                 db/jdbc-opts)))

(defn reorder-task [ds user-id task-id new-sort-order]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:update :tasks
                 :set {:sort_order new-sort-order}
                 :where [:and [:= :id task-id] (db/user-id-where-clause user-id)]}))
  {:success true :sort_order new-sort-order})

(defn set-task-due-date [ds user-id task-id due-date]
  (let [set-map (if (nil? due-date)
                  {:due_date due-date
                   :due_time nil
                   :modified_at [:raw "datetime('now')"]}
                  {:due_date due-date
                   :modified_at [:raw "datetime('now')"]})]
    (jdbc/execute-one! (db/get-conn ds)
      (sql/format {:update :tasks
                   :set set-map
                   :where [:and [:= :id task-id] (db/user-id-where-clause user-id)]
                   :returning [:id :due_date :due_time :modified_at]})
      db/jdbc-opts)))

(defn set-task-due-time [ds user-id task-id due-time]
  (let [normalized-time (if (empty? due-time) nil due-time)]
    (jdbc/execute-one! (db/get-conn ds)
      (sql/format {:update :tasks
                   :set {:due_time normalized-time
                         :modified_at [:raw "datetime('now')"]}
                   :where [:and [:= :id task-id] (db/user-id-where-clause user-id)]
                   :returning [:id :due_date :due_time :modified_at]})
      db/jdbc-opts)))

(defn delete-task [ds user-id task-id]
  (when (task-owned-by-user? ds task-id user-id)
    (let [conn (db/get-conn ds)]
      (jdbc/with-transaction [tx conn]
        (jdbc/execute-one! tx
          (sql/format {:delete-from :task_categories
                       :where [:= :task_id task-id]}))
        (jdbc/execute-one! tx
          (sql/format {:delete-from :relations
                       :where [:or
                               [:and [:= :source_type "tsk"] [:= :source_id task-id]]
                               [:and [:= :target_type "tsk"] [:= :target_id task-id]]]}))
        (let [result (jdbc/execute-one! tx
                       (sql/format {:delete-from :tasks
                                    :where [:= :id task-id]}))]
          (tel/log! {:level :info :data {:task-id task-id :user-id user-id}} "Task deleted")
          {:success (pos? (:next.jdbc/update-count result))})))))

(defn set-task-done [ds user-id task-id done?]
  (let [done-val (if done? 1 0)]
    (jdbc/execute-one! (db/get-conn ds)
      (sql/format {:update :tasks
                   :set {:done done-val
                         :modified_at [:raw "datetime('now')"]}
                   :where [:and [:= :id task-id] (db/user-id-where-clause user-id)]
                   :returning [:id :done :modified_at]})
      db/jdbc-opts)))

(defn set-task-today [ds user-id task-id today?]
  (let [today-val (if today? 1 0)]
    (jdbc/execute-one! (db/get-conn ds)
      (sql/format {:update :tasks
                   :set {:today today-val
                         :modified_at [:raw "datetime('now')"]}
                   :where [:and [:= :id task-id] (db/user-id-where-clause user-id)]
                   :returning [:id :today :modified_at]})
      db/jdbc-opts)))

(defn set-task-field [ds user-id task-id field value]
  (let [normalize-fn (get db/field-normalizers field identity)
        valid-value (normalize-fn value)]
    (jdbc/execute-one! (db/get-conn ds)
      (sql/format {:update :tasks
                   :set {field valid-value
                         :modified_at [:raw "datetime('now')"]}
                   :where [:and [:= :id task-id] (db/user-id-where-clause user-id)]
                   :returning [:id field :modified_at]})
      db/jdbc-opts)))
