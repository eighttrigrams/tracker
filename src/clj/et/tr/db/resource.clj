(ns et.tr.db.resource
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [taoensso.telemere :as tel]
            [et.tr.db :as db]
            [et.tr.db.relation :as relation]))

(defn add-resource [ds user-id title link scope]
  (let [conn (db/get-conn ds)
        valid-scope (db/normalize-scope scope)
        min-order (or (:min_order (jdbc/execute-one! conn
                                    (sql/format {:select [[[:min :sort_order] :min_order]]
                                                 :from [:resources]
                                                 :where (db/user-id-where-clause user-id)})
                                    db/jdbc-opts))
                      1.0)
        new-order (- min-order 1.0)
        result (jdbc/execute-one! conn
                 (sql/format {:insert-into :resources
                              :values [{:title title
                                        :link link
                                        :sort_order new-order
                                        :user_id user-id
                                        :modified_at [:raw "datetime('now')"]
                                        :scope valid-scope}]
                              :returning (conj db/resource-select-columns :user_id)})
                 db/jdbc-opts)]
    (tel/log! {:level :info :data {:resource-id (:id result) :user-id user-id}} "Resource added")
    (assoc result :people [] :places [] :projects [] :goals [])))

(defn- build-resource-category-clauses [categories]
  (let [people-clause (db/build-category-subquery :resource_categories :resource_id :resources "person" (:people categories))
        places-clause (db/build-category-subquery :resource_categories :resource_id :resources "place" (:places categories))
        projects-clause (db/build-category-subquery :resource_categories :resource_id :resources "project" (:projects categories))
        goals-clause (db/build-category-subquery :resource_categories :resource_id :resources "goal" (:goals categories))]
    (filterv some? [people-clause places-clause projects-clause goals-clause])))

(defn- associate-categories-with-resources [resources categories-by-resource people-by-id places-by-id projects-by-id goals-by-id]
  (mapv (fn [resource]
          (let [resource-categories (get categories-by-resource (:id resource) [])]
            (assoc resource
                   :people (db/extract-category resource-categories "person" people-by-id)
                   :places (db/extract-category resource-categories "place" places-by-id)
                   :projects (db/extract-category resource-categories "project" projects-by-id)
                   :goals (db/extract-category resource-categories "goal" goals-by-id))))
        resources))

(defn- domain-match-clause [domain]
  (if (= domain "Sheet")
    [:or [:is :link nil] [:= :link ""]]
    [:or
     [:like :link (str "%://" domain "/%")]
     [:like :link (str "%://www." domain "/%")]]))

(defn- domain-exclude-clause [domain]
  (if (= domain "Sheet")
    [:not [:or [:is :link nil] [:= :link ""]]]
    [:or
     [:is :link nil]
     [:= :link ""]
     [:not [:or
            [:like :link (str "%://" domain "/%")]
            [:like :link (str "%://www." domain "/%")]]]]))

(defn list-resources
  ([ds user-id] (list-resources ds user-id {}))
  ([ds user-id opts]
   (let [{:keys [search-term importance context strict categories domain excluded-domains sort-mode]} opts
         conn (db/get-conn ds)
         user-where (db/user-id-where-clause user-id)
         search-clause (db/build-search-clause search-term [:title :tags :link])
         importance-clause (db/build-importance-clause importance)
         scope-clause (db/build-scope-clause context strict)
         domain-clause (when (and domain (seq domain))
                         (domain-match-clause domain))
         excluded-domains-clause (when (seq excluded-domains)
                                   (into [:and] (map domain-exclude-clause excluded-domains)))
         category-clauses (build-resource-category-clauses categories)
         where-clause (into [:and user-where]
                            (concat (filter some? [search-clause importance-clause scope-clause domain-clause excluded-domains-clause])
                                    category-clauses))
         resources (jdbc/execute! conn
                     (sql/format {:select db/resource-select-columns
                                  :from [:resources]
                                  :where where-clause
                                  :order-by (case sort-mode
                                              "added" [[:created_at :desc]]
                                              [[:sort_order :asc]])})
                     db/jdbc-opts)
         resource-ids (mapv :id resources)
         categories-data (when (seq resource-ids)
                           (jdbc/execute! conn
                             (sql/format {:select [:resource_id :category_type :category_id]
                                          :from [:resource_categories]
                                          :where [:in :resource_id resource-ids]})
                             db/jdbc-opts))
         {:keys [people-by-id places-by-id projects-by-id goals-by-id]} (db/fetch-category-lookups conn user-where)
         categories-by-resource (group-by :resource_id categories-data)
         resources-with-categories (associate-categories-with-resources resources categories-by-resource people-by-id places-by-id projects-by-id goals-by-id)]
     (relation/associate-relations-with-items resources-with-categories "res" conn))))

(defn resource-owned-by-user? [ds resource-id user-id]
  (some? (jdbc/execute-one! (db/get-conn ds)
           (sql/format {:select [:id]
                        :from [:resources]
                        :where [:and [:= :id resource-id] (db/user-id-where-clause user-id)]})
           db/jdbc-opts)))

(defn get-resource [ds user-id resource-id]
  (let [conn (db/get-conn ds)
        user-where (db/user-id-where-clause user-id)
        resource (jdbc/execute-one! conn
                   (sql/format {:select db/resource-select-columns
                                :from [:resources]
                                :where [:and [:= :id resource-id] user-where]})
                   db/jdbc-opts)]
    (when resource
      (let [categories-data (jdbc/execute! conn
                              (sql/format {:select [:resource_id :category_type :category_id]
                                           :from [:resource_categories]
                                           :where [:= :resource_id resource-id]})
                              db/jdbc-opts)
            {:keys [people-by-id places-by-id projects-by-id goals-by-id]} (db/fetch-category-lookups conn user-where)
            categories-by-resource (group-by :resource_id categories-data)]
        (first (associate-categories-with-resources [resource] categories-by-resource people-by-id places-by-id projects-by-id goals-by-id))))))

(defn reorder-resource [ds user-id resource-id new-sort-order]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:update :resources
                 :set {:sort_order new-sort-order}
                 :where [:and [:= :id resource-id] (db/user-id-where-clause user-id)]}))
  {:success true :sort_order new-sort-order})

(defn update-resource [ds user-id resource-id fields]
  (let [field-names (keys fields)
        set-map (assoc fields :modified_at [:raw "datetime('now')"])
        return-cols (into [:id :created_at :modified_at] field-names)]
    (jdbc/execute-one! (db/get-conn ds)
      (sql/format {:update :resources
                   :set set-map
                   :where [:and [:= :id resource-id] (db/user-id-where-clause user-id)]
                   :returning return-cols})
      db/jdbc-opts)))

(defn convert-message-to-resource [ds user-id message-id link & {:keys [title]}]
  (let [conn (db/get-conn ds)]
    (jdbc/with-transaction [tx conn]
      (when-let [message (jdbc/execute-one! tx
                           (sql/format {:select [:id :sender :title :description :annotation :scope :importance]
                                        :from [:messages]
                                        :where [:and [:= :id message-id] (db/user-id-where-clause user-id)]})
                           db/jdbc-opts)]
        (let [description (str (or (:description message) "")
                              (when (and (seq (:description message)) (seq (:annotation message))) "\n")
                              (or (:annotation message) ""))
              min-order (or (:min_order (jdbc/execute-one! tx
                                          (sql/format {:select [[[:min :sort_order] :min_order]]
                                                       :from [:resources]
                                                       :where (db/user-id-where-clause user-id)})
                                          db/jdbc-opts))
                            1.0)
              new-order (- min-order 1.0)
              resource (jdbc/execute-one! tx
                         (sql/format {:insert-into :resources
                                      :values [{:title (or title (:title message))
                                                :link link
                                                :sort_order new-order
                                                :user_id user-id
                                                :modified_at [:raw "datetime('now')"]
                                                :scope (or (:scope message) "both")
                                                :importance (or (:importance message) "normal")}]
                                      :returning (conj db/resource-select-columns :user_id)})
                         db/jdbc-opts)]
          (when (seq description)
            (jdbc/execute-one! tx
              (sql/format {:update :resources
                           :set {:description description :modified_at [:raw "datetime('now')"]}
                           :where [:and [:= :id (:id resource)] (db/user-id-where-clause user-id)]})
              db/jdbc-opts))
          (jdbc/execute-one! tx
            (sql/format {:delete-from :messages
                         :where [:= :id message-id]}))
          (tel/log! {:level :info :data {:message-id message-id :resource-id (:id resource) :user-id user-id}} "Message converted to resource")
          (assoc resource :description description :people [] :projects []))))))

(defn delete-resource [ds user-id resource-id]
  (when (resource-owned-by-user? ds resource-id user-id)
    (let [conn (db/get-conn ds)]
      (jdbc/with-transaction [tx conn]
        (jdbc/execute-one! tx
          (sql/format {:delete-from :resource_categories
                       :where [:= :resource_id resource-id]}))
        (jdbc/execute-one! tx
          (sql/format {:delete-from :relations
                       :where [:or
                               [:and [:= :source_type "res"] [:= :source_id resource-id]]
                               [:and [:= :target_type "res"] [:= :target_id resource-id]]]}))
        (let [result (jdbc/execute-one! tx
                       (sql/format {:delete-from :resources
                                    :where [:= :id resource-id]}))]
          (tel/log! {:level :info :data {:resource-id resource-id :user-id user-id}} "Resource deleted")
          {:success (pos? (:next.jdbc/update-count result))})))))

(defn set-resource-field [ds user-id resource-id field value]
  (let [normalize-fn (get db/field-normalizers field identity)
        valid-value (normalize-fn value)]
    (jdbc/execute-one! (db/get-conn ds)
      (sql/format {:update :resources
                   :set {field valid-value
                         :modified_at [:raw "datetime('now')"]}
                   :where [:and [:= :id resource-id] (db/user-id-where-clause user-id)]
                   :returning [:id field :modified_at]})
      db/jdbc-opts)))

(defn categorize-resource [ds user-id resource-id category-type category-id]
  (db/validate-category-type! category-type)
  (when (and (resource-owned-by-user? ds resource-id user-id)
             (db/category-owned-by-user? ds category-type category-id user-id))
    (let [conn (db/get-conn ds)]
      (jdbc/with-transaction [tx conn]
        (jdbc/execute-one! tx
          (sql/format {:insert-into :resource_categories
                       :values [{:resource_id resource-id
                                 :category_type category-type
                                 :category_id category-id}]
                       :on-conflict []
                       :do-nothing true}))
        (jdbc/execute-one! tx
          (sql/format {:update :resources
                       :set {:modified_at [:raw "datetime('now')"]}
                       :where [:= :id resource-id]}))))))

(defn uncategorize-resource [ds user-id resource-id category-type category-id]
  (db/validate-category-type! category-type)
  (when (and (resource-owned-by-user? ds resource-id user-id)
             (db/category-owned-by-user? ds category-type category-id user-id))
    (let [conn (db/get-conn ds)]
      (jdbc/with-transaction [tx conn]
        (jdbc/execute-one! tx
          (sql/format {:delete-from :resource_categories
                       :where [:and
                               [:= :resource_id resource-id]
                               [:= :category_type category-type]
                               [:= :category_id category-id]]}))
        (jdbc/execute-one! tx
          (sql/format {:update :resources
                       :set {:modified_at [:raw "datetime('now')"]}
                       :where [:= :id resource-id]}))))))
