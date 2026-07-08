(ns et.tr.db.issue
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [taoensso.telemere :as tel]
            [et.tr.db :as db]
            [et.tr.db.relation :as relation]))

(defn add-issue
  ([ds user-id title] (add-issue ds user-id title "both"))
  ([ds user-id title scope]
   (let [conn (db/get-conn ds)
         valid-scope (db/normalize-scope scope)
         min-order (or (:min_order (jdbc/execute-one! conn
                                     (sql/format {:select [[[:min :sort_order] :min_order]]
                                                  :from [:issues]
                                                  :where (db/user-id-where-clause user-id)})
                                     db/jdbc-opts))
                       1.0)
         new-order (- min-order 1.0)
         result (jdbc/execute-one! conn
                  (sql/format {:insert-into :issues
                               :values [{:title title
                                         :sort_order new-order
                                         :user_id user-id
                                         :modified_at [:raw "datetime('now')"]
                                         :scope valid-scope}]
                               :returning (conj db/issue-select-columns :user_id)})
                  db/jdbc-opts)]
     (tel/log! {:level :info :data {:issue-id (:id result) :user-id user-id}} "Issue added")
     (assoc result :people [] :places [] :projects [] :goals [] :tasks []))))

(defn- build-issue-category-clauses [categories]
  (let [people-clause (db/build-category-subquery :issue_categories :issue_id :issues "person" (:people categories))
        places-clause (db/build-category-subquery :issue_categories :issue_id :issues "place" (:places categories))
        projects-clause (db/build-category-subquery :issue_categories :issue_id :issues "project" (:projects categories))
        goals-clause (db/build-category-subquery :issue_categories :issue_id :issues "goal" (:goals categories))]
    (filterv some? [people-clause places-clause projects-clause goals-clause])))

(defn- associate-categories-with-issues [issues categories-by-issue people-by-id places-by-id projects-by-id goals-by-id]
  (mapv (fn [issue]
          (let [issue-categories (get categories-by-issue (:id issue) [])]
            (assoc issue
                   :people (db/extract-category issue-categories "person" people-by-id)
                   :places (db/extract-category issue-categories "place" places-by-id)
                   :projects (db/extract-category issue-categories "project" projects-by-id)
                   :goals (db/extract-category issue-categories "goal" goals-by-id))))
        issues))

(defn- fetch-tasks-by-issue [conn issue-ids]
  (when (seq issue-ids)
    (jdbc/execute! conn
      (sql/format {:select [:id :title :done :issue_id]
                   :from [:tasks]
                   :where [:in :issue_id issue-ids]
                   :order-by [[:done :asc] [:sort_order :asc] [:id :asc]]})
      db/jdbc-opts)))

(defn- associate-tasks-with-issues [issues conn]
  (let [issue-ids (mapv :id issues)
        tasks (fetch-tasks-by-issue conn issue-ids)
        tasks-by-issue (group-by :issue_id tasks)]
    (mapv (fn [issue]
            (assoc issue :tasks
                   (mapv #(select-keys % [:id :title :done])
                         (get tasks-by-issue (:id issue) []))))
          issues)))

(defn list-issues
  ([ds user-id] (list-issues ds user-id {}))
  ([ds user-id opts]
   (let [{:keys [search-term importance urgency context strict categories sort-mode limit offset]} opts
         conn (db/get-conn ds)
         user-where (db/user-id-where-clause user-id)
         search-clause (db/build-search-clause search-term [:title :tags])
         importance-clause (db/build-importance-clause importance)
         urgency-clause (db/build-urgency-clause urgency)
         scope-clause (db/build-scope-clause context strict)
         category-clauses (build-issue-category-clauses categories)
         resolved-clause [:= :resolved (if (= sort-mode "resolved") 1 0)]
         where-clause (into [:and user-where resolved-clause]
                            (concat (filter some? [search-clause importance-clause urgency-clause scope-clause])
                                    category-clauses))
         issues (jdbc/execute! conn
                  (sql/format (cond-> {:select db/issue-select-columns
                                       :from [:issues]
                                       :where where-clause
                                       :order-by (case sort-mode
                                                   "added" [[:created_at :desc] [:id :desc]]
                                                   "manual" [[:sort_order :asc] [:id :asc]]
                                                   "resolved" [[:resolved_at :desc] [:id :desc]]
                                                   [[:modified_at :desc] [:id :desc]])}
                                limit (assoc :limit limit)
                                offset (assoc :offset offset)))
                  db/jdbc-opts)
         issue-ids (mapv :id issues)
         categories-data (when (seq issue-ids)
                           (jdbc/execute! conn
                             (sql/format {:select [:issue_id :category_type :category_id]
                                          :from [:issue_categories]
                                          :where [:in :issue_id issue-ids]})
                             db/jdbc-opts))
         {:keys [people-by-id places-by-id projects-by-id goals-by-id]} (db/fetch-category-lookups conn user-where {:context context :strict strict})
         categories-by-issue (group-by :issue_id categories-data)
         issues-with-categories (associate-categories-with-issues issues categories-by-issue people-by-id places-by-id projects-by-id goals-by-id)
         issues-with-tasks (associate-tasks-with-issues issues-with-categories conn)]
     (relation/associate-relations-with-items issues-with-tasks "iss" conn))))

(defn issue-owned-by-user? [ds issue-id user-id]
  (some? (jdbc/execute-one! (db/get-conn ds)
           (sql/format {:select [:id]
                        :from [:issues]
                        :where [:and [:= :id issue-id] (db/user-id-where-clause user-id)]})
           db/jdbc-opts)))

(defn get-issue [ds user-id issue-id]
  (let [conn (db/get-conn ds)
        user-where (db/user-id-where-clause user-id)
        issue (jdbc/execute-one! conn
                (sql/format {:select db/issue-select-columns
                             :from [:issues]
                             :where [:and [:= :id issue-id] user-where]})
                db/jdbc-opts)]
    (when issue
      (let [categories-data (jdbc/execute! conn
                              (sql/format {:select [:issue_id :category_type :category_id]
                                           :from [:issue_categories]
                                           :where [:= :issue_id issue-id]})
                              db/jdbc-opts)
            {:keys [people-by-id places-by-id projects-by-id goals-by-id]} (db/fetch-category-lookups conn user-where)
            categories-by-issue (group-by :issue_id categories-data)]
        (-> (associate-categories-with-issues [issue] categories-by-issue people-by-id places-by-id projects-by-id goals-by-id)
            (associate-tasks-with-issues conn)
            first)))))

(defn reorder-issue [ds user-id issue-id new-sort-order]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:update :issues
                 :set {:sort_order new-sort-order}
                 :where [:and [:= :id issue-id] (db/user-id-where-clause user-id)]}))
  {:success true :sort_order new-sort-order})

(defn update-issue
  ([ds user-id issue-id fields] (update-issue ds user-id issue-id fields nil))
  ([ds user-id issue-id fields expected-modified-at]
   (let [set-map (assoc fields :modified_at [:raw "datetime('now')"])
         return-cols (into [:id :created_at :modified_at] (keys fields))]
     (jdbc/execute-one! (db/get-conn ds)
       (sql/format {:update :issues
                    :set set-map
                    :where (db/update-where issue-id user-id expected-modified-at)
                    :returning return-cols})
       db/jdbc-opts))))

(defn delete-issue [ds user-id issue-id]
  (when (issue-owned-by-user? ds issue-id user-id)
    (let [conn (db/get-conn ds)]
      (jdbc/with-transaction [tx conn]
        (jdbc/execute-one! tx
          (sql/format {:update :tasks
                       :set {:issue_id nil
                             :modified_at [:raw "datetime('now')"]}
                       :where [:= :issue_id issue-id]}))
        (jdbc/execute-one! tx
          (sql/format {:delete-from :issue_categories
                       :where [:= :issue_id issue-id]}))
        (relation/delete-relations-for-item tx "iss" issue-id)
        (let [result (jdbc/execute-one! tx
                       (sql/format {:delete-from :issues
                                    :where [:= :id issue-id]}))]
          (tel/log! {:level :info :data {:issue-id issue-id :user-id user-id}} "Issue deleted")
          {:success (pos? (:next.jdbc/update-count result))})))))

(defn set-issue-field [ds user-id issue-id field value]
  (let [normalize-fn (get db/field-normalizers field identity)
        valid-value (normalize-fn value)]
    (jdbc/execute-one! (db/get-conn ds)
      (sql/format {:update :issues
                   :set {field valid-value
                         :modified_at [:raw "datetime('now')"]}
                   :where [:and [:= :id issue-id] (db/user-id-where-clause user-id)]
                   :returning [:id field :modified_at]})
      db/jdbc-opts)))

(defn- undone-task-count
  "Number of the caller's tasks belonging to the issue that are not yet done."
  [conn user-id issue-id]
  (:c (jdbc/execute-one! conn
        (sql/format {:select [[[:count :*] :c]]
                     :from [:tasks]
                     :where [:and (db/user-id-where-clause user-id)
                             [:= :issue_id issue-id]
                             [:= :done 0]]})
        db/jdbc-opts)))

(defn set-issue-resolved
  "Toggle an issue's resolved flag (mirrors db.task/set-task-done). Resolving
  stamps resolved_at; reopening nulls it. Refuses to resolve while any belonging
  task is still undone, returning {:error :undone-tasks} so the handler can emit
  a 4xx. Returns the updated row on success, or nil when no owned row matched."
  [ds user-id issue-id resolved?]
  (let [conn (db/get-conn ds)]
    (if (and resolved? (pos? (undone-task-count conn user-id issue-id)))
      {:error :undone-tasks}
      (jdbc/execute-one! conn
        (sql/format {:update :issues
                     :set (cond-> {:resolved (if resolved? 1 0)
                                   :modified_at [:raw "datetime('now')"]}
                            resolved? (assoc :resolved_at [:raw "datetime('now')"])
                            (not resolved?) (assoc :resolved_at nil))
                     :where [:and [:= :id issue-id] (db/user-id-where-clause user-id)]
                     :returning [:id :resolved :resolved_at :modified_at]})
        db/jdbc-opts))))

(defn categorize-issue [ds user-id issue-id category-type category-id]
  (db/validate-category-type! category-type)
  (when (and (issue-owned-by-user? ds issue-id user-id)
             (db/category-owned-by-user? ds category-type category-id user-id))
    (let [conn (db/get-conn ds)]
      (jdbc/with-transaction [tx conn]
        (jdbc/execute-one! tx
          (sql/format {:insert-into :issue_categories
                       :values [{:issue_id issue-id
                                 :category_type category-type
                                 :category_id category-id}]
                       :on-conflict []
                       :do-nothing true}))
        (jdbc/execute-one! tx
          (sql/format {:update :issues
                       :set {:modified_at [:raw "datetime('now')"]}
                       :where [:= :id issue-id]}))))))

(defn uncategorize-issue [ds user-id issue-id category-type category-id]
  (db/validate-category-type! category-type)
  (when (and (issue-owned-by-user? ds issue-id user-id)
             (db/category-owned-by-user? ds category-type category-id user-id))
    (let [conn (db/get-conn ds)]
      (jdbc/with-transaction [tx conn]
        (jdbc/execute-one! tx
          (sql/format {:delete-from :issue_categories
                       :where [:and
                               [:= :issue_id issue-id]
                               [:= :category_type category-type]
                               [:= :category_id category-id]]}))
        (jdbc/execute-one! tx
          (sql/format {:update :issues
                       :set {:modified_at [:raw "datetime('now')"]}
                       :where [:= :id issue-id]}))))))

(defn task-owned-by-user? [ds task-id user-id]
  (some? (jdbc/execute-one! (db/get-conn ds)
           (sql/format {:select [:id]
                        :from [:tasks]
                        :where [:and [:= :id task-id] (db/user-id-where-clause user-id)]})
           db/jdbc-opts)))

(defn set-task-issue
  "Establish the belongs-to link: point a task at an issue by setting the
  task's issue_id FK. Both the task and the issue must be owned by the caller.
  Returns {:success true :previous-issue-id <id-or-nil>} on success (where
  :previous-issue-id is the issue the task belonged to beforehand, so callers
  can audit an implicit reassignment), nil otherwise."
  [ds user-id task-id issue-id]
  (when (and (task-owned-by-user? ds task-id user-id)
             (issue-owned-by-user? ds issue-id user-id))
    (let [conn (db/get-conn ds)
          previous-issue-id (:issue_id (jdbc/execute-one! conn
                                         (sql/format {:select [:issue_id]
                                                      :from [:tasks]
                                                      :where [:and [:= :id task-id] (db/user-id-where-clause user-id)]})
                                         db/jdbc-opts))]
      (jdbc/execute-one! conn
        (sql/format {:update :tasks
                     :set {:issue_id issue-id
                           :modified_at [:raw "datetime('now')"]}
                     :where [:and [:= :id task-id] (db/user-id-where-clause user-id)]}))
      {:success true :previous-issue-id previous-issue-id})))

(defn clear-task-issue
  "Clear the belongs-to link: null out the task's issue_id FK. Only clears when
  the task is currently linked to the given issue. Returns {:success true} when
  a row was updated, nil otherwise."
  [ds user-id task-id issue-id]
  (when (task-owned-by-user? ds task-id user-id)
    (let [result (jdbc/execute-one! (db/get-conn ds)
                   (sql/format {:update :tasks
                                :set {:issue_id nil
                                      :modified_at [:raw "datetime('now')"]}
                                :where [:and [:= :id task-id]
                                        [:= :issue_id issue-id]
                                        (db/user-id-where-clause user-id)]}))]
      (when (pos? (:next.jdbc/update-count result))
        {:success true}))))
