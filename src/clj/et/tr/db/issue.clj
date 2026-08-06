(ns et.tr.db.issue
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [taoensso.telemere :as tel]
            [et.tr.clock :as clock]
            [et.tr.db :as db]
            [et.tr.ordering :as ordering]
            [et.tr.db.category-rule :as db.category-rule]
            [et.tr.db.category-exclusion :as db.category-exclusion]
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
     (merge result db/empty-category-groups {:tasks []}))))

(defn- build-issue-category-clauses [categories]
  (db/build-category-clauses :issue_categories :issue_id :issues categories))

(defn- associate-categories-with-issues [issues categories-by-issue lookups]
  (db/assoc-category-groups issues categories-by-issue lookups))

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
   (let [{:keys [search-term importance urgency context strict categories excluded-categories sort-mode limit offset date-from date-to]} opts
         conn (db/get-conn ds)
         user-where (db/user-id-where-clause user-id)
         search-clause (db/build-search-clause search-term [:title :tags])
         importance-clause (db/build-importance-clause importance)
         urgency-clause (db/build-urgency-clause urgency)
         scope-clause (db/build-scope-clause context strict)
         category-clauses (build-issue-category-clauses categories)
         exclusion-clauses (db.category-exclusion/build-exclusion-clauses ds user-id :issue_categories :issue_id :issues excluded-categories)
         ;; A date window always targets resolved issues (mirrors how the
         ;; report windows tasks on done_at): windowing over resolved_at only
         ;; makes sense for resolved rows.
         resolved? (or (= sort-mode "resolved") (some? date-from) (some? date-to))
         resolved-clause [:= :resolved (if resolved? 1 0)]
         date-range-clause (db/build-date-range-clause :resolved_at date-from date-to)
         where-clause (into [:and user-where resolved-clause]
                            (concat (filter some? [search-clause importance-clause urgency-clause scope-clause date-range-clause])
                                    category-clauses
                                    exclusion-clauses))
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
         lookups (db/fetch-category-lookups conn user-where {:context context :strict strict})
         categories-by-issue (group-by :issue_id categories-data)
         issues-with-categories (associate-categories-with-issues issues categories-by-issue lookups)
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
            lookups (db/fetch-category-lookups conn user-where)
            categories-by-issue (group-by :issue_id categories-data)]
        (-> (associate-categories-with-issues [issue] categories-by-issue lookups)
            (associate-tasks-with-issues conn)
            first)))))

(defn reorder-issue [ds user-id issue-id new-sort-order]
  (db/write-order! ds :issues-page user-id issue-id new-sort-order))

(defn list-urgent-issues
  "The caller's unresolved issues of one urgency, in the order Urgent Matters
  shows them — just the columns its reorder arithmetic needs."
  [ds user-id urgency]
  (let [col (ordering/column :issues-urgent)]
    (jdbc/execute! (db/get-conn ds)
      (sql/format {:select [:id col]
                   :from [:issues]
                   :where [:and (db/user-id-where-clause user-id)
                           [:= :resolved 0]
                           [:= :urgency urgency]]
                   :order-by [[col :asc] [:id :asc]]})
      db/jdbc-opts)))

(defn reorder-issue-in-urgent [ds user-id issue-id new-order]
  (db/write-order! ds :issues-urgent user-id issue-id new-order))

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

(defn- issue-urgency [ds user-id issue-id]
  (:urgency (jdbc/execute-one! (db/get-conn ds)
              (sql/format {:select [:urgency]
                           :from [:issues]
                           :where [:and [:= :id issue-id] (db/user-id-where-clause user-id)]})
              db/jdbc-opts)))

(defn place-in-urgent-list!
  "Urgent Matters is an ordering context of its own, so an issue entering it is
  given a position — the top of its urgency's block — and one leaving it gives
  that position up. Mirrors db.task/place-in-urgent-list!."
  [ds user-id issue-id urgency]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:update :issues
                 :set (ordering/positioning :issues-urgent
                                            (when (contains? db/urgent-urgencies urgency)
                                              (db/top-of-order ds :issues-urgent user-id [:= :urgency urgency])))
                 :where [:and [:= :id issue-id] (db/user-id-where-clause user-id)]})))

(defn set-issue-field [ds user-id issue-id field value]
  (let [normalize-fn (get db/field-normalizers field identity)
        valid-value (normalize-fn value)
        urgency-before (when (= field :urgency) (issue-urgency ds user-id issue-id))
        result (jdbc/execute-one! (db/get-conn ds)
                 (sql/format {:update :issues
                              :set {field valid-value
                                    :modified_at [:raw "datetime('now')"]}
                              :where [:and [:= :id issue-id] (db/user-id-where-clause user-id)]
                              :returning [:id field :modified_at]})
                 db/jdbc-opts)]
    (when (and result (= field :urgency) (not= urgency-before valid-value))
      (place-in-urgent-list! ds user-id issue-id valid-value))
    result))

(defn urgent-position [ds user-id issue-id]
  ((ordering/column :issues-urgent)
   (jdbc/execute-one! (db/get-conn ds)
     (sql/format {:select [(ordering/column :issues-urgent)]
                  :from [:issues]
                  :where [:and [:= :id issue-id] (db/user-id-where-clause user-id)]})
     db/jdbc-opts)))

(defn join-urgent!
  "Put the issue in the Urgent Matters block `urgency` renders. Returns the
  before/after urgency when it wrote, nil when it was already there. Mirrors
  db.task/join-urgent!: the urgency write and the position write both land on
  sort_order_urgent, so they are one server-side operation rather than two
  requests whose arrival order would decide the result."
  [ds user-id issue-id urgency]
  (let [before (issue-urgency ds user-id issue-id)]
    (when (and before (not= before urgency))
      (set-issue-field ds user-id issue-id :urgency urgency)
      {:before {:urgency before} :after {:urgency urgency}})))

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
                            resolved? (assoc :resolved_at (clock/sql-now))
                            (not resolved?) (assoc :resolved_at nil))
                     :where [:and [:= :id issue-id] (db/user-id-where-clause user-id)]
                     :returning [:id :resolved :resolved_at :modified_at]})
        db/jdbc-opts))))

(defn- issue-task-count
  "Number of the caller's tasks belonging to the issue, done or undone —
  conversion's guard, which is stricter than `undone-task-count`'s on purpose:
  resolving claims the work finished, converting claims this Issue *is* the
  work, and a done Task hanging off it contradicts that just as loudly."
  [conn user-id issue-id]
  (:c (jdbc/execute-one! conn
        (sql/format {:select [[[:count :*] :c]]
                     :from [:tasks]
                     :where [:and (db/user-id-where-clause user-id)
                             [:= :issue_id issue-id]]})
        db/jdbc-opts)))

(defn convert-issue-to-task
  "Turn an Issue into a Task. The Issue is gone afterwards and nothing points
  back at it, which is what makes this the opposite of `set-task-issue`'s
  belongs-to link rather than a variant of it.

  Everything about the content moves: title, description, tags, scope,
  importance, urgency, relation_badge_title, the Issue's categories, and its
  relations — which are re-pointed rather than dropped, because the item at the
  other end still means the relation. The manual orderings do not: the Issues
  list and the Tasks list are two orders and neither is derived from the other,
  so the Task lands where `add-task` puts a new Task (top of the Tasks page) and,
  when it is urgent, at the top of its urgency's block in Urgent Matters — the
  position an item entering that context is given, not the Issue's own.

  One transaction, because a half-done conversion would leave either an Issue
  whose categories are gone or a Task duplicating a live Issue.

  Returns the created task row on success, {:error :has-tasks} when any Task
  belongs to the Issue, {:error :resolved} for a resolved Issue, and nil when no
  Issue of the caller's has that id."
  [ds user-id issue-id]
  (jdbc/with-transaction [tx (db/get-conn ds)]
    (let [issue (jdbc/execute-one! tx
                  (sql/format {:select db/issue-select-columns
                               :from [:issues]
                               :where [:and [:= :id issue-id] (db/user-id-where-clause user-id)]})
                  db/jdbc-opts)]
      (cond
        (nil? issue) nil
        (= 1 (:resolved issue)) {:error :resolved}
        (pos? (issue-task-count tx user-id issue-id)) {:error :has-tasks}

        :else
        ;; Both positions come from db/top-of-order rather than being computed
        ;; here: it is the one place the step-below-the-minimum scheme lives, and
        ;; place-in-urgent-list! already gives an arriving Task its urgent
        ;; position through it. Passing `tx` keeps both reads inside this
        ;; transaction.
        (let [urgent? (contains? db/urgent-urgencies (:urgency issue))
              task (jdbc/execute-one! tx
                     (sql/format {:insert-into :tasks
                                  :values [(cond-> {:title (:title issue)
                                                    :description (:description issue)
                                                    :tags (:tags issue)
                                                    :scope (:scope issue)
                                                    :importance (:importance issue)
                                                    :urgency (:urgency issue)
                                                    :relation_badge_title (:relation_badge_title issue)
                                                    :sort_order (db/top-of-order tx :tasks-page user-id)
                                                    :user_id user-id
                                                    :modified_at (clock/sql-now)}
                                             urgent? (assoc (ordering/column :tasks-urgent)
                                                            (db/top-of-order tx :tasks-urgent user-id
                                                                             [:= :urgency (:urgency issue)])))]
                                  :returning db/task-select-columns})
                     db/jdbc-opts)
              task-id (:id task)
              categories (jdbc/execute! tx
                           (sql/format {:select [:category_type :category_id]
                                        :from [:issue_categories]
                                        :where [:= :issue_id issue-id]})
                           db/jdbc-opts)]
          (when (seq categories)
            (jdbc/execute-one! tx
              (sql/format {:insert-into :task_categories
                           :values (mapv (fn [{:keys [category_type category_id]}]
                                           {:task_id task-id
                                            :category_type category_type
                                            :category_id category_id})
                                         categories)})))
          ;; Relations are stored in both directions, so both ends are re-pointed.
          ;; UNIQUE (source_type, source_id, target_type, target_id) cannot be hit
          ;; by this: the Task was created a moment ago and has no relations of its
          ;; own for a re-pointed row to collide with.
          (jdbc/execute-one! tx
            (sql/format {:update :relations
                         :set {:source_type "tsk" :source_id task-id}
                         :where [:and [:= :source_type "iss"] [:= :source_id issue-id]]}))
          (jdbc/execute-one! tx
            (sql/format {:update :relations
                         :set {:target_type "tsk" :target_id task-id}
                         :where [:and [:= :target_type "iss"] [:= :target_id issue-id]]}))
          ;; No tasks to detach — the guard above refused the Issue if any belonged
          ;; to it — so unlike delete-issue this only clears the join rows.
          (jdbc/execute-one! tx
            (sql/format {:delete-from :issue_categories
                         :where [:= :issue_id issue-id]}))
          (jdbc/execute-one! tx
            (sql/format {:delete-from :issues
                         :where [:= :id issue-id]}))
          (tel/log! {:level :info :data {:issue-id issue-id :task-id task-id :user-id user-id}}
                    "Issue converted to task")
          task)))))

(defn categorize-issue [ds user-id issue-id category-type category-id]
  (db/validate-category-type! category-type)
  (when (and (issue-owned-by-user? ds issue-id user-id)
             (db/category-owned-by-user? ds category-type category-id user-id))
    (let [conn (db/get-conn ds)
          closure (db.category-rule/resolve-closure ds user-id [[category-type category-id]])]
      (jdbc/with-transaction [tx conn]
        (let [applied (db.category-rule/apply-closure! tx :issue_categories :issue_id issue-id closure)]
          (jdbc/execute-one! tx
            (sql/format {:update :issues
                         :set {:modified_at [:raw "datetime('now')"]}
                         :where [:= :id issue-id]}))
          applied)))))

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
