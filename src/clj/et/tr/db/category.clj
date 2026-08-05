(ns et.tr.db.category
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [taoensso.telemere :as tel]
            [et.tr.db :as db]
            [et.tr.db.category-rule :as db.category-rule]))

;; Since 073-unify-category-tables every Category Group lives in the one
;; `categories` table and the group is the row's category_type. What used to be
;; "which of the four tables" is now "which value of one column", so these
;; functions take a category-type throughout and the per-group entry points at
;; the bottom just pass their own.

(defn- add-category [ds user-id name category-type]
  (db/validate-category-type! category-type)
  (let [conn (db/get-conn ds)
        max-order (or (:max_order (jdbc/execute-one! conn
                                    (sql/format {:select [[[:max :sort_order] :max_order]]
                                                 :from [:categories]
                                                 :where [:and
                                                         (db/category-type-where category-type)
                                                         (db/user-id-where-clause user-id)]})
                                    db/jdbc-opts))
                      0)
        new-order (+ max-order 1.0)
        result (jdbc/execute-one! conn
                 (sql/format {:insert-into :categories
                              :values [{:category_type category-type
                                        :name name :user_id user-id :sort_order new-order
                                        :modified_at [:raw "datetime('now')"]}]
                              :returning [:id :name :tags :sort_order :badge_title :scope :modified_at :category_type]})
                 db/jdbc-opts)]
    (tel/log! {:level :info :data {:category category-type :id (:id result) :user-id user-id}} "Category added")
    result))

(defn add-person [ds user-id name] (add-category ds user-id name "person"))
(defn add-place [ds user-id name] (add-category ds user-id name "place"))
(defn add-workstream [ds user-id name] (add-category ds user-id name "workstream"))
(defn add-project [ds user-id name] (add-category ds user-id name "project"))
(defn add-goal [ds user-id name] (add-category ds user-id name "goal"))
(defn add-asset [ds user-id name] (add-category ds user-id name "asset"))

(defn- list-category
  ([ds user-id category-type] (list-category ds user-id category-type nil))
  ([ds user-id category-type {:keys [search-term context strict]}]
   (db/validate-category-type! category-type)
   (let [user-where (db/user-id-where-clause user-id)
         search-clause (db/build-search-clause search-term [:name :badge_title :tags])
         scope-clause (db/build-scope-clause context strict)
         where-clause (into [:and user-where (db/category-type-where category-type)]
                            (filter some? [search-clause scope-clause]))]
     (jdbc/execute! (db/get-conn ds)
       (sql/format {:select [:id :name :description :tags :sort_order :badge_title :scope :modified_at :category_type]
                    :from [:categories]
                    :where where-clause
                    :order-by [[:modified_at :desc] [:name :asc]]})
       db/jdbc-opts))))

(defn list-people
  ([ds user-id] (list-category ds user-id "person"))
  ([ds user-id opts] (list-category ds user-id "person" opts)))

(defn list-places
  ([ds user-id] (list-category ds user-id "place"))
  ([ds user-id opts] (list-category ds user-id "place" opts)))

(defn list-workstreams
  ([ds user-id] (list-category ds user-id "workstream"))
  ([ds user-id opts] (list-category ds user-id "workstream" opts)))

(defn list-projects
  ([ds user-id] (list-category ds user-id "project"))
  ([ds user-id opts] (list-category ds user-id "project" opts)))

(defn list-goals
  ([ds user-id] (list-category ds user-id "goal"))
  ([ds user-id opts] (list-category ds user-id "goal" opts)))

(defn list-assets
  ([ds user-id] (list-category ds user-id "asset"))
  ([ds user-id opts] (list-category ds user-id "asset" opts)))

(def list-fn-for-type
  "Group type -> the list-* fn for that group, for callers (routes, reorder)
  that pick one at runtime."
  {"person" list-people
   "place" list-places
   "workstream" list-workstreams
   "project" list-projects
   "goal" list-goals
   "asset" list-assets})

(defn get-category
  "Fetch one category owned by `user-id`. `category-type` may be nil to look the
  row up by id alone, which is what the group-change endpoint needs: it has to
  find the item before it knows which group the item is currently in."
  [ds user-id category-id category-type]
  (when category-type (db/validate-category-type! category-type))
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:select [:id :name :description :tags :sort_order :badge_title :scope :modified_at :category_type]
                 :from [:categories]
                 :where (into [:and [:= :id category-id] (db/user-id-where-clause user-id)]
                              (when category-type [(db/category-type-where category-type)]))})
    db/jdbc-opts))

(defn- update-category
  ([ds user-id category-id name description tags badge-title category-type]
   (update-category ds user-id category-id name description tags badge-title category-type nil))
  ([ds user-id category-id name description tags badge-title category-type expected-modified-at]
   (db/validate-category-type! category-type)
   (jdbc/execute-one! (db/get-conn ds)
     (sql/format {:update :categories
                  :set {:name name :description description :tags tags :badge_title (or badge-title "")
                        :modified_at [:raw "datetime('now')"]}
                  :where (conj (db/update-where category-id user-id expected-modified-at)
                               (db/category-type-where category-type))
                  :returning [:id :name :description :tags :badge_title :scope :modified_at :category_type]})
     db/jdbc-opts)))

(defn update-person
  ([ds user-id id name description tags badge-title]
   (update-category ds user-id id name description tags badge-title "person"))
  ([ds user-id id name description tags badge-title expected-modified-at]
   (update-category ds user-id id name description tags badge-title "person" expected-modified-at)))

(defn update-place
  ([ds user-id id name description tags badge-title]
   (update-category ds user-id id name description tags badge-title "place"))
  ([ds user-id id name description tags badge-title expected-modified-at]
   (update-category ds user-id id name description tags badge-title "place" expected-modified-at)))

(defn update-workstream
  ([ds user-id id name description tags badge-title]
   (update-category ds user-id id name description tags badge-title "workstream"))
  ([ds user-id id name description tags badge-title expected-modified-at]
   (update-category ds user-id id name description tags badge-title "workstream" expected-modified-at)))

(defn update-project
  ([ds user-id id name description tags badge-title]
   (update-category ds user-id id name description tags badge-title "project"))
  ([ds user-id id name description tags badge-title expected-modified-at]
   (update-category ds user-id id name description tags badge-title "project" expected-modified-at)))

(defn update-goal
  ([ds user-id id name description tags badge-title]
   (update-category ds user-id id name description tags badge-title "goal"))
  ([ds user-id id name description tags badge-title expected-modified-at]
   (update-category ds user-id id name description tags badge-title "goal" expected-modified-at)))

(defn update-asset
  ([ds user-id id name description tags badge-title]
   (update-category ds user-id id name description tags badge-title "asset"))
  ([ds user-id id name description tags badge-title expected-modified-at]
   (update-category ds user-id id name description tags badge-title "asset" expected-modified-at)))

(def join-tables
  "The eight <entity>_categories tables, with the column naming their entity and
  the entity table that column points into. Every one of them mirrors
  categories.category_type, so anything that changes or removes a category has
  to visit all eight."
  [[:task_categories :task_id :tasks]
   [:issue_categories :issue_id :issues]
   [:resource_categories :resource_id :resources]
   [:meet_categories :meet_id :meets]
   [:meeting_series_categories :meeting_series_id :meeting_series]
   [:recurring_task_categories :recurring_task_id :recurring_tasks]
   [:journal_categories :journal_id :journals]
   [:journal_entry_categories :journal_entry_id :journal_entries]])

(defn delete-category [ds user-id category-id category-type]
  (db/validate-category-type! category-type)
  (let [conn (db/get-conn ds)
        category-where [:and [:= :category_type category-type] [:= :category_id category-id]]]
    (jdbc/with-transaction [tx conn]
      ;; NOTE: three of the eight join tables, not all eight -- unchanged from
      ;; before the unification. Deleting a category therefore still leaves
      ;; rows behind in issue_categories, meeting_series_categories,
      ;; recurring_task_categories, journal_categories and
      ;; journal_entry_categories. That is a pre-existing gap and widening it
      ;; here would change what a delete does to the owner's data, so it is
      ;; reported rather than quietly repaired.
      (doseq [[join-table entity-col entity-table]
              [[:task_categories :task_id :tasks]
               [:resource_categories :resource_id :resources]
               [:meet_categories :meet_id :meets]]]
        (jdbc/execute-one! tx
          (sql/format {:delete-from join-table
                       :where (conj category-where
                                    [:in entity-col {:select [:id]
                                                     :from [entity-table]
                                                     :where (db/user-id-where-clause user-id)}])})))
      (db.category-rule/delete-rules-for-category tx user-id category-type category-id)
      (let [result (jdbc/execute-one! tx
                     (sql/format {:delete-from :categories
                                  :where [:and [:= :id category-id]
                                          (db/category-type-where category-type)
                                          (db/user-id-where-clause user-id)]}))]
        (tel/log! {:level :info :data {:category category-type :id category-id :user-id user-id}} "Category deleted")
        {:success (pos? (:next.jdbc/update-count result))}))))

(defn get-category-sort-order [ds user-id category-id category-type]
  (db/validate-category-type! category-type)
  (:sort_order (jdbc/execute-one! (db/get-conn ds)
                 (sql/format {:select [:sort_order]
                              :from [:categories]
                              :where [:and [:= :id category-id]
                                      (db/category-type-where category-type)
                                      (db/user-id-where-clause user-id)]})
                 db/jdbc-opts)))

(defn reorder-category [ds user-id category-id new-sort-order context]
  (db/write-order! ds context user-id category-id new-sort-order))

(defn- set-category-field [ds user-id category-id field value category-type]
  (db/validate-category-type! category-type)
  (let [normalize-fn (get db/field-normalizers field identity)
        valid-value (normalize-fn value)]
    (jdbc/execute-one! (db/get-conn ds)
      (sql/format {:update :categories
                   :set {field valid-value
                         :modified_at [:raw "datetime('now')"]}
                   :where [:and [:= :id category-id]
                           (db/category-type-where category-type)
                           (db/user-id-where-clause user-id)]
                   :returning [:id field :modified_at]})
      db/jdbc-opts)))

(defn set-person-field [ds user-id id field value] (set-category-field ds user-id id field value "person"))
(defn set-place-field [ds user-id id field value] (set-category-field ds user-id id field value "place"))
(defn set-workstream-field [ds user-id id field value] (set-category-field ds user-id id field value "workstream"))
(defn set-project-field [ds user-id id field value] (set-category-field ds user-id id field value "project"))
(defn set-goal-field [ds user-id id field value] (set-category-field ds user-id id field value "goal"))
(defn set-asset-field [ds user-id id field value] (set-category-field ds user-id id field value "asset"))
