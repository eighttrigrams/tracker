(ns et.tr.db.category
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [taoensso.telemere :as tel]
            [et.tr.db :as db]))

(def ^:private valid-category-tables #{"people" "places" "projects" "goals"})

(defn- validate-table-name! [table-name]
  (when-not (contains? valid-category-tables table-name)
    (throw (ex-info "Invalid table name" {:table-name table-name}))))

(defn- add-category [ds user-id name table-name]
  (validate-table-name! table-name)
  (let [conn (db/get-conn ds)
        max-order (or (:max_order (jdbc/execute-one! conn
                                    (sql/format {:select [[[:max :sort_order] :max_order]]
                                                 :from [(keyword table-name)]
                                                 :where (db/user-id-where-clause user-id)})
                                    db/jdbc-opts))
                      0)
        new-order (+ max-order 1.0)
        result (jdbc/execute-one! conn
                 (sql/format {:insert-into (keyword table-name)
                              :values [{:name name :user_id user-id :sort_order new-order
                                        :modified_at [:raw "datetime('now')"]}]
                              :returning [:id :name :tags :sort_order :badge_title :modified_at]})
                 db/jdbc-opts)]
    (tel/log! {:level :info :data {:category table-name :id (:id result) :user-id user-id}} "Category added")
    result))

(defn add-person [ds user-id name]
  (add-category ds user-id name "people"))

(defn add-place [ds user-id name]
  (add-category ds user-id name "places"))

(defn add-project [ds user-id name]
  (add-category ds user-id name "projects"))

(defn add-goal [ds user-id name]
  (add-category ds user-id name "goals"))

(defn- list-category
  ([ds user-id table-name] (list-category ds user-id table-name nil))
  ([ds user-id table-name {:keys [search-term]}]
   (validate-table-name! table-name)
   (let [user-where (db/user-id-where-clause user-id)
         search-clause (db/build-search-clause search-term [:name :badge_title :tags])
         where-clause (if search-clause [:and user-where search-clause] user-where)]
     (jdbc/execute! (db/get-conn ds)
       (sql/format {:select [:id :name :description :tags :sort_order :badge_title :modified_at]
                    :from [(keyword table-name)]
                    :where where-clause
                    :order-by [[:modified_at :desc] [:name :asc]]})
       db/jdbc-opts))))

(defn list-people
  ([ds user-id] (list-category ds user-id "people"))
  ([ds user-id opts] (list-category ds user-id "people" opts)))

(defn list-places
  ([ds user-id] (list-category ds user-id "places"))
  ([ds user-id opts] (list-category ds user-id "places" opts)))

(defn list-projects
  ([ds user-id] (list-category ds user-id "projects"))
  ([ds user-id opts] (list-category ds user-id "projects" opts)))

(defn list-goals
  ([ds user-id] (list-category ds user-id "goals"))
  ([ds user-id opts] (list-category ds user-id "goals" opts)))

(defn get-category [ds user-id category-id table-name]
  (validate-table-name! table-name)
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:select [:id :name :description :tags :sort_order :badge_title :modified_at]
                 :from [(keyword table-name)]
                 :where [:and [:= :id category-id] (db/user-id-where-clause user-id)]})
    db/jdbc-opts))

(defn- update-category
  ([ds user-id category-id name description tags badge-title table-name]
   (update-category ds user-id category-id name description tags badge-title table-name nil))
  ([ds user-id category-id name description tags badge-title table-name expected-modified-at]
   (validate-table-name! table-name)
   (jdbc/execute-one! (db/get-conn ds)
     (sql/format {:update (keyword table-name)
                  :set {:name name :description description :tags tags :badge_title (or badge-title "")
                        :modified_at [:raw "datetime('now')"]}
                  :where (db/update-where category-id user-id expected-modified-at)
                  :returning [:id :name :description :tags :badge_title :modified_at]})
     db/jdbc-opts)))

(defn update-person
  ([ds user-id person-id name description tags badge-title]
   (update-category ds user-id person-id name description tags badge-title "people"))
  ([ds user-id person-id name description tags badge-title expected-modified-at]
   (update-category ds user-id person-id name description tags badge-title "people" expected-modified-at)))

(defn update-place
  ([ds user-id place-id name description tags badge-title]
   (update-category ds user-id place-id name description tags badge-title "places"))
  ([ds user-id place-id name description tags badge-title expected-modified-at]
   (update-category ds user-id place-id name description tags badge-title "places" expected-modified-at)))

(defn update-project
  ([ds user-id project-id name description tags badge-title]
   (update-category ds user-id project-id name description tags badge-title "projects"))
  ([ds user-id project-id name description tags badge-title expected-modified-at]
   (update-category ds user-id project-id name description tags badge-title "projects" expected-modified-at)))

(defn update-goal
  ([ds user-id goal-id name description tags badge-title]
   (update-category ds user-id goal-id name description tags badge-title "goals"))
  ([ds user-id goal-id name description tags badge-title expected-modified-at]
   (update-category ds user-id goal-id name description tags badge-title "goals" expected-modified-at)))

(defn delete-category [ds user-id category-id category-type table-name]
  (validate-table-name! table-name)
  (db/validate-category-type! category-type)
  (let [conn (db/get-conn ds)
        category-where [:and [:= :category_type category-type] [:= :category_id category-id]]]
    (jdbc/with-transaction [tx conn]
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
      (let [result (jdbc/execute-one! tx
                     (sql/format {:delete-from (keyword table-name)
                                  :where [:and [:= :id category-id] (db/user-id-where-clause user-id)]}))]
        (tel/log! {:level :info :data {:category table-name :id category-id :user-id user-id}} "Category deleted")
        {:success (pos? (:next.jdbc/update-count result))}))))

(defn get-category-sort-order [ds user-id category-id table-name]
  (validate-table-name! table-name)
  (:sort_order (jdbc/execute-one! (db/get-conn ds)
                 (sql/format {:select [:sort_order]
                              :from [(keyword table-name)]
                              :where [:and [:= :id category-id] (db/user-id-where-clause user-id)]})
                 db/jdbc-opts)))

(defn reorder-category [ds user-id category-id new-sort-order table-name]
  (validate-table-name! table-name)
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:update (keyword table-name)
                 :set {:sort_order new-sort-order}
                 :where [:and [:= :id category-id] (db/user-id-where-clause user-id)]}))
  {:success true :sort_order new-sort-order})
