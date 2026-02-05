(ns et.tr.db
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [et.tr.migrations :as migrations]
            [buddy.hashers :as hashers]
            [clojure.string]
            [honey.sql :as sql]
            [taoensso.telemere :as tel]))

(def ^:private jdbc-opts {:builder-fn rs/as-unqualified-maps})

(defn init-conn [{:keys [type path]}]
  (let [db-spec (case type
                  :sqlite-memory {:dbtype "sqlite" :dbname "file::memory:?cache=shared"}
                  :sqlite-file {:dbtype "sqlite" :dbname path})
        ds (jdbc/get-datasource db-spec)
        persistent-conn (when (= type :sqlite-memory) (jdbc/get-connection ds))
        conn-for-use (or persistent-conn ds)]
    (migrations/migrate! conn-for-use)
    {:conn conn-for-use
     :persistent-conn persistent-conn
     :type type}))

(defn- get-conn [ds]
  (if (map? ds) (:conn ds) ds))

(defn create-user [ds username password]
  (let [hash (hashers/derive password)
        result (jdbc/execute-one! (get-conn ds)
                 (sql/format {:insert-into :users
                              :values [{:username username :password_hash hash}]
                              :returning [:id :username :language :created_at]})
                 jdbc-opts)]
    (tel/log! {:level :info :data {:user-id (:id result) :username username}} "User created")
    result))

(defn get-user-by-username [ds username]
  (jdbc/execute-one! (get-conn ds)
    (sql/format {:select [:id :username :password_hash :language :created_at]
                 :from [:users]
                 :where [:= :username username]})
    jdbc-opts))

(defn verify-user [ds username password]
  (when-let [user (get-user-by-username ds username)]
    (when (hashers/check password (:password_hash user))
      (dissoc user :password_hash))))

(defn list-users [ds]
  (jdbc/execute! (get-conn ds)
    (sql/format {:select [:id :username :language :created_at]
                 :from [:users]
                 :where [:not= :username "admin"]
                 :order-by [[:created_at :asc]]})
    jdbc-opts))

(defn delete-user [ds user-id]
  (let [conn (get-conn ds)]
    (jdbc/with-transaction [tx conn]
      (let [task-ids (mapv :id (jdbc/execute! tx
                                 (sql/format {:select [:id]
                                              :from [:tasks]
                                              :where [:= :user_id user-id]})
                                 jdbc-opts))]
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

(def valid-scopes #{"private" "both" "work"})

(defn- normalize-scope [scope]
  (if (contains? valid-scopes scope) scope "both"))

(def valid-importances #{"normal" "important" "critical"})

(defn- normalize-importance [importance]
  (if (contains? valid-importances importance) importance "normal"))

(def valid-urgencies #{"default" "urgent" "superurgent"})

(defn- normalize-urgency [urgency]
  (if (contains? valid-urgencies urgency) urgency "default"))

(def task-select-columns [:id :title :description :tags :created_at :modified_at :due_date :due_time :sort_order :done :scope :importance :urgency])

(defn- user-id-where-clause [user-id]
  (if user-id
    [:= :user_id user-id]
    [:is :user_id nil]))

(defn add-task
  ([ds user-id title] (add-task ds user-id title "both"))
  ([ds user-id title scope]
   (let [conn (get-conn ds)
         valid-scope (normalize-scope scope)
         min-order (or (:min_order (jdbc/execute-one! conn
                                     (sql/format {:select [[[:min :sort_order] :min_order]]
                                                  :from [:tasks]
                                                  :where (user-id-where-clause user-id)})
                                     jdbc-opts))
                       1.0)
         new-order (- min-order 1.0)
         result (jdbc/execute-one! conn
                  (sql/format {:insert-into :tasks
                               :values [{:title title
                                         :sort_order new-order
                                         :user_id user-id
                                         :modified_at [:raw "datetime('now')"]
                                         :scope valid-scope}]
                               :returning (conj task-select-columns :user_id)})
                  jdbc-opts)]
     (tel/log! {:level :info :data {:task-id (:id result) :user-id user-id}} "Task added")
     result)))

(defn- extract-category [task-categories category-type lookup-map]
  (->> task-categories
       (filter #(= (:category_type %) category-type))
       (keep #(when-let [name (lookup-map (:category_id %))]
                {:id (:category_id %) :name name}))
       vec))

(defn- associate-categories-with-tasks [tasks categories-by-task people-by-id places-by-id projects-by-id goals-by-id]
  (mapv (fn [task]
          (let [task-categories (get categories-by-task (:id task) [])]
            (assoc task
                   :people (extract-category task-categories "person" people-by-id)
                   :places (extract-category task-categories "place" places-by-id)
                   :projects (extract-category task-categories "project" projects-by-id)
                   :goals (extract-category task-categories "goal" goals-by-id))))
        tasks))

(defn- build-search-clause [search-term]
  (when (and search-term (not (clojure.string/blank? search-term)))
    (let [terms (->> (clojure.string/split (clojure.string/trim search-term) #"\s+")
                     (map clojure.string/lower-case)
                     (filter (complement clojure.string/blank?)))]
      (when (seq terms)
        (into [:and]
              (map (fn [term]
                     [:or
                      [:like [:lower :title] (str term "%")]
                      [:like [:lower :title] (str "% " term "%")]
                      [:like [:lower :tags] (str term "%")]
                      [:like [:lower :tags] (str "% " term "%")]])
                   terms))))))

(defn- build-category-subquery [category-type category-names]
  (when (seq category-names)
    (let [table-name (case category-type
                       "person" :people
                       "place" :places
                       "project" :projects
                       "goal" :goals)]
      [:exists {:select [1]
                :from [:task_categories]
                :join [[table-name] [:= (keyword (str (name table-name) ".id")) :task_categories.category_id]]
                :where [:and
                        [:= :task_categories.task_id :tasks.id]
                        [:= :task_categories.category_type category-type]
                        [:in (keyword (str (name table-name) ".name")) category-names]]}])))

(defn- build-category-clauses [categories]
  (let [people-clause (build-category-subquery "person" (:people categories))
        places-clause (build-category-subquery "place" (:places categories))
        projects-clause (build-category-subquery "project" (:projects categories))
        goals-clause (build-category-subquery "goal" (:goals categories))]
    (filterv some? [people-clause places-clause projects-clause goals-clause])))

(defn- build-exclusion-subquery [category-type category-names]
  (when (seq category-names)
    (let [table-name (case category-type
                       "place" :places
                       "project" :projects)]
      [:not [:exists {:select [1]
                      :from [:task_categories]
                      :join [[table-name] [:= (keyword (str (name table-name) ".id")) :task_categories.category_id]]
                      :where [:and
                              [:= :task_categories.task_id :tasks.id]
                              [:= :task_categories.category_type category-type]
                              [:in (keyword (str (name table-name) ".name")) category-names]]}]])))

(defn- fetch-category-lookups [conn user-id-where-clause]
  (let [people (jdbc/execute! conn
                 (sql/format {:select [:id :name]
                              :from [:people]
                              :where user-id-where-clause})
                 jdbc-opts)
        places (jdbc/execute! conn
                 (sql/format {:select [:id :name]
                              :from [:places]
                              :where user-id-where-clause})
                 jdbc-opts)
        projects (jdbc/execute! conn
                   (sql/format {:select [:id :name]
                                :from [:projects]
                                :where user-id-where-clause})
                   jdbc-opts)
        goals (jdbc/execute! conn
                (sql/format {:select [:id :name]
                              :from [:goals]
                              :where user-id-where-clause})
                jdbc-opts)]
    {:people-by-id (into {} (map (juxt :id :name) people))
     :places-by-id (into {} (map (juxt :id :name) places))
     :projects-by-id (into {} (map (juxt :id :name) projects))
     :goals-by-id (into {} (map (juxt :id :name) goals))}))

(defn list-tasks
  ([ds user-id] (list-tasks ds user-id :recent))
  ([ds user-id sort-mode] (list-tasks ds user-id sort-mode nil))
  ([ds user-id sort-mode opts]
   (let [opts (if (string? opts) {:search-term opts} opts)
         {:keys [search-term importance context strict categories excluded-places excluded-projects]} opts
         conn (get-conn ds)
         user-where (user-id-where-clause user-id)
         base-where (case sort-mode
                      :due-date [:and user-where [:not= :due_date nil] [:= :done 0]]
                      :done [:and user-where [:= :done 1]]
                      :today [:and user-where [:= :done 0]
                              [:or [:not= :due_date nil]
                                   [:in :urgency ["urgent" "superurgent"]]]]
                      [:and user-where [:= :done 0]])
         search-clause (build-search-clause search-term)
         importance-clause (case importance
                             "important" [:in :importance ["important" "critical"]]
                             "critical" [:= :importance "critical"]
                             nil)
         scope-clause (when context
                        (if strict
                          [:= :scope context]
                          (case context
                            "private" [:in :scope ["private" "both"]]
                            "work" [:in :scope ["work" "both"]]
                            nil)))
         category-clauses (build-category-clauses categories)
         exclusion-clauses (filterv some? [(build-exclusion-subquery "place" excluded-places)
                                           (build-exclusion-subquery "project" excluded-projects)])
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
                 (sql/format {:select task-select-columns
                              :from [:tasks]
                              :where where-clause
                              :order-by order-by})
                 jdbc-opts)
         task-ids (mapv :id tasks)
         categories (when (seq task-ids)
                      (jdbc/execute! conn
                        (sql/format {:select [:task_id :category_type :category_id]
                                     :from [:task_categories]
                                     :where [:in :task_id task-ids]})
                        jdbc-opts))
         {:keys [people-by-id places-by-id projects-by-id goals-by-id]} (fetch-category-lookups conn user-where)
         categories-by-task (group-by :task_id categories)]
     (associate-categories-with-tasks tasks categories-by-task people-by-id places-by-id projects-by-id goals-by-id))))

(def ^:private valid-category-tables #{"people" "places" "projects" "goals"})

(defn- validate-table-name! [table-name]
  (when-not (contains? valid-category-tables table-name)
    (throw (ex-info "Invalid table name" {:table-name table-name}))))

(def ^:private valid-category-types #{"person" "place" "project" "goal"})

(defn- validate-category-type! [category-type]
  (when-not (contains? valid-category-types category-type)
    (throw (ex-info "Invalid category type" {:category-type category-type}))))

(defn- add-category [ds user-id name table-name]
  (validate-table-name! table-name)
  (let [conn (get-conn ds)
        max-order (or (:max_order (jdbc/execute-one! conn
                                    (sql/format {:select [[[:max :sort_order] :max_order]]
                                                 :from [(keyword table-name)]
                                                 :where (user-id-where-clause user-id)})
                                    jdbc-opts))
                      0)
        new-order (+ max-order 1.0)
        result (jdbc/execute-one! conn
                 (sql/format {:insert-into (keyword table-name)
                              :values [{:name name :user_id user-id :sort_order new-order}]
                              :returning [:id :name :sort_order]})
                 jdbc-opts)]
    (tel/log! {:level :info :data {:category table-name :id (:id result) :user-id user-id}} "Category added")
    result))

(defn add-person [ds user-id name]
  (add-category ds user-id name "people"))

(defn add-place [ds user-id name]
  (add-category ds user-id name "places"))

(defn- list-category [ds user-id table-name]
  (validate-table-name! table-name)
  (jdbc/execute! (get-conn ds)
    (sql/format {:select [:id :name :description :sort_order]
                 :from [(keyword table-name)]
                 :where (user-id-where-clause user-id)
                 :order-by [[:sort_order :asc] [:name :asc]]})
    jdbc-opts))

(defn list-people [ds user-id]
  (list-category ds user-id "people"))

(defn list-places [ds user-id]
  (list-category ds user-id "places"))

(defn add-project [ds user-id name]
  (add-category ds user-id name "projects"))

(defn add-goal [ds user-id name]
  (add-category ds user-id name "goals"))

(defn list-projects [ds user-id]
  (list-category ds user-id "projects"))

(defn list-goals [ds user-id]
  (list-category ds user-id "goals"))

(defn- update-category [ds user-id category-id name description table-name]
  (validate-table-name! table-name)
  (jdbc/execute-one! (get-conn ds)
    (sql/format {:update (keyword table-name)
                 :set {:name name :description description}
                 :where [:and [:= :id category-id] (user-id-where-clause user-id)]
                 :returning [:id :name :description]})
    jdbc-opts))

(defn update-person [ds user-id person-id name description]
  (update-category ds user-id person-id name description "people"))

(defn update-place [ds user-id place-id name description]
  (update-category ds user-id place-id name description "places"))

(defn update-project [ds user-id project-id name description]
  (update-category ds user-id project-id name description "projects"))

(defn update-goal [ds user-id goal-id name description]
  (update-category ds user-id goal-id name description "goals"))

(defn delete-category [ds user-id category-id category-type table-name]
  (validate-table-name! table-name)
  (validate-category-type! category-type)
  (let [conn (get-conn ds)]
    (jdbc/with-transaction [tx conn]
      (jdbc/execute-one! tx
        (sql/format {:delete-from :task_categories
                     :where [:and
                             [:= :category_type category-type]
                             [:= :category_id category-id]
                             [:in :task_id {:select [:id]
                                            :from [:tasks]
                                            :where (user-id-where-clause user-id)}]]}))
      (let [result (jdbc/execute-one! tx
                     (sql/format {:delete-from (keyword table-name)
                                  :where [:and [:= :id category-id] (user-id-where-clause user-id)]}))]
        (tel/log! {:level :info :data {:category table-name :id category-id :user-id user-id}} "Category deleted")
        {:success (pos? (:next.jdbc/update-count result))}))))

(defn task-owned-by-user? [ds task-id user-id]
  (some? (jdbc/execute-one! (get-conn ds)
           (sql/format {:select [:id]
                        :from [:tasks]
                        :where [:and [:= :id task-id] (user-id-where-clause user-id)]})
           jdbc-opts)))

(defn categorize-task [ds user-id task-id category-type category-id]
  (validate-category-type! category-type)
  (when (task-owned-by-user? ds task-id user-id)
    (let [conn (get-conn ds)]
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
  (validate-category-type! category-type)
  (when (task-owned-by-user? ds task-id user-id)
    (let [conn (get-conn ds)]
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
    (jdbc/execute-one! (get-conn ds)
      (sql/format {:update :tasks
                   :set set-map
                   :where [:and [:= :id task-id] (user-id-where-clause user-id)]
                   :returning return-cols})
      jdbc-opts)))

(defn get-task-sort-order [ds user-id task-id]
  (:sort_order (jdbc/execute-one! (get-conn ds)
                 (sql/format {:select [:sort_order]
                              :from [:tasks]
                              :where [:and [:= :id task-id] (user-id-where-clause user-id)]})
                 jdbc-opts)))

(defn reorder-task [ds user-id task-id new-sort-order]
  (jdbc/execute-one! (get-conn ds)
    (sql/format {:update :tasks
                 :set {:sort_order new-sort-order}
                 :where [:and [:= :id task-id] (user-id-where-clause user-id)]}))
  {:success true :sort_order new-sort-order})

(defn get-category-sort-order [ds user-id category-id table-name]
  (validate-table-name! table-name)
  (:sort_order (jdbc/execute-one! (get-conn ds)
                 (sql/format {:select [:sort_order]
                              :from [(keyword table-name)]
                              :where [:and [:= :id category-id] (user-id-where-clause user-id)]})
                 jdbc-opts)))

(defn reorder-category [ds user-id category-id new-sort-order table-name]
  (validate-table-name! table-name)
  (jdbc/execute-one! (get-conn ds)
    (sql/format {:update (keyword table-name)
                 :set {:sort_order new-sort-order}
                 :where [:and [:= :id category-id] (user-id-where-clause user-id)]}))
  {:success true :sort_order new-sort-order})

(defn set-task-due-date [ds user-id task-id due-date]
  (let [set-map (if (nil? due-date)
                  {:due_date due-date
                   :due_time nil
                   :modified_at [:raw "datetime('now')"]}
                  {:due_date due-date
                   :modified_at [:raw "datetime('now')"]})]
    (jdbc/execute-one! (get-conn ds)
      (sql/format {:update :tasks
                   :set set-map
                   :where [:and [:= :id task-id] (user-id-where-clause user-id)]
                   :returning [:id :due_date :due_time :modified_at]})
      jdbc-opts)))

(defn set-task-due-time [ds user-id task-id due-time]
  (let [normalized-time (if (empty? due-time) nil due-time)]
    (jdbc/execute-one! (get-conn ds)
      (sql/format {:update :tasks
                   :set {:due_time normalized-time
                         :modified_at [:raw "datetime('now')"]}
                   :where [:and [:= :id task-id] (user-id-where-clause user-id)]
                   :returning [:id :due_date :due_time :modified_at]})
      jdbc-opts)))

(defn delete-task [ds user-id task-id]
  (when (task-owned-by-user? ds task-id user-id)
    (let [conn (get-conn ds)]
      (jdbc/with-transaction [tx conn]
        (jdbc/execute-one! tx
          (sql/format {:delete-from :task_categories
                       :where [:= :task_id task-id]}))
        (let [result (jdbc/execute-one! tx
                       (sql/format {:delete-from :tasks
                                    :where [:= :id task-id]}))]
          (tel/log! {:level :info :data {:task-id task-id :user-id user-id}} "Task deleted")
          {:success (pos? (:next.jdbc/update-count result))})))))

(defn set-task-done [ds user-id task-id done?]
  (let [done-val (if done? 1 0)]
    (jdbc/execute-one! (get-conn ds)
      (sql/format {:update :tasks
                   :set {:done done-val
                         :modified_at [:raw "datetime('now')"]}
                   :where [:and [:= :id task-id] (user-id-where-clause user-id)]
                   :returning [:id :done :modified_at]})
      jdbc-opts)))

(def ^:private field-normalizers
  {:scope normalize-scope
   :importance normalize-importance
   :urgency normalize-urgency})

(defn set-task-field [ds user-id task-id field value]
  (let [normalize-fn (get field-normalizers field identity)
        valid-value (normalize-fn value)]
    (jdbc/execute-one! (get-conn ds)
      (sql/format {:update :tasks
                   :set {field valid-value
                         :modified_at [:raw "datetime('now')"]}
                   :where [:and [:= :id task-id] (user-id-where-clause user-id)]
                   :returning [:id field :modified_at]})
      jdbc-opts)))

(def valid-languages #{"en" "de" "pt"})

(defn get-user-language [ds user-id]
  (when user-id
    (:language (jdbc/execute-one! (get-conn ds)
                 (sql/format {:select [:language]
                              :from [:users]
                              :where [:= :id user-id]})
                 jdbc-opts))))

(defn set-user-language [ds user-id language]
  (when (and user-id (contains? valid-languages language))
    (jdbc/execute-one! (get-conn ds)
      (sql/format {:update :users
                   :set {:language language}
                   :where [:= :id user-id]
                   :returning [:id :language]})
      jdbc-opts)))

(defn- query-categories-chunked [conn task-ids]
  (if (empty? task-ids)
    []
    (let [chunk-size 500
          chunks (partition-all chunk-size task-ids)]
      (mapcat (fn [chunk]
                (jdbc/execute! conn
                  (sql/format {:select [:task_id :category_type :category_id]
                               :from [:task_categories]
                               :where [:in :task_id (vec chunk)]})
                  jdbc-opts))
              chunks))))

(defn- normalize-task [task]
  (-> task
      (update :description #(or % ""))
      (update :sort_order #(or % 0.0))
      (update :due_time #(when (and % (not= % "")) %))))

(defn export-all-data [ds user-id]
  (let [conn (get-conn ds)
        user-where (user-id-where-clause user-id)
        tasks (jdbc/execute! conn
                (sql/format {:select task-select-columns
                             :from [:tasks]
                             :where user-where
                             :order-by [[:created_at :asc]]})
                jdbc-opts)
        task-ids (mapv :id tasks)
        categories (query-categories-chunked conn task-ids)
        people (jdbc/execute! conn
                 (sql/format {:select [:id :name :description :sort_order]
                              :from [:people]
                              :where user-where
                              :order-by [[:sort_order :asc] [:name :asc]]})
                 jdbc-opts)
        places (jdbc/execute! conn
                 (sql/format {:select [:id :name :description :sort_order]
                              :from [:places]
                              :where user-where
                              :order-by [[:sort_order :asc] [:name :asc]]})
                 jdbc-opts)
        projects (jdbc/execute! conn
                   (sql/format {:select [:id :name :description :sort_order]
                                :from [:projects]
                                :where user-where
                                :order-by [[:sort_order :asc] [:name :asc]]})
                   jdbc-opts)
        goals (jdbc/execute! conn
                (sql/format {:select [:id :name :description :sort_order]
                             :from [:goals]
                             :where user-where
                             :order-by [[:sort_order :asc] [:name :asc]]})
                jdbc-opts)
        people-by-id (into {} (map (juxt :id :name) people))
        places-by-id (into {} (map (juxt :id :name) places))
        projects-by-id (into {} (map (juxt :id :name) projects))
        goals-by-id (into {} (map (juxt :id :name) goals))
        categories-by-task (group-by :task_id categories)
        tasks-with-categories (->> (associate-categories-with-tasks tasks categories-by-task people-by-id places-by-id projects-by-id goals-by-id)
                                   (mapv normalize-task))]
    {:tasks tasks-with-categories
     :people people
     :places places
     :projects projects
     :goals goals}))

(defn add-message [ds user-id sender title description]
  (let [result (jdbc/execute-one! (get-conn ds)
                 (sql/format {:insert-into :messages
                              :values [{:sender sender
                                        :title title
                                        :description (or description "")
                                        :user_id user-id}]
                              :returning [:id :sender :title :description :created_at :done :user_id]})
                 jdbc-opts)]
    (tel/log! {:level :info :data {:message-id (:id result) :user-id user-id}} "Message added")
    result))

(defn list-messages
  ([ds user-id] (list-messages ds user-id :recent nil))
  ([ds user-id sort-mode] (list-messages ds user-id sort-mode nil))
  ([ds user-id sort-mode sender-filter]
   (let [user-where (user-id-where-clause user-id)
         done-filter (case sort-mode
                       :done [:= :done 1]
                       [:= :done 0])
         where-clause (if sender-filter
                        [:and user-where done-filter [:= :sender sender-filter]]
                        [:and user-where done-filter])]
     (jdbc/execute! (get-conn ds)
       (sql/format {:select [:id :sender :title :description :created_at :done :annotation]
                    :from [:messages]
                    :where where-clause
                    :order-by [[:created_at :desc]]})
       jdbc-opts))))

(defn message-owned-by-user? [ds message-id user-id]
  (some? (jdbc/execute-one! (get-conn ds)
           (sql/format {:select [:id]
                        :from [:messages]
                        :where [:and [:= :id message-id] (user-id-where-clause user-id)]})
           jdbc-opts)))

(defn set-message-done [ds user-id message-id done?]
  (let [done-val (if done? 1 0)]
    (jdbc/execute-one! (get-conn ds)
      (sql/format {:update :messages
                   :set {:done done-val}
                   :where [:and [:= :id message-id] (user-id-where-clause user-id)]
                   :returning [:id :done]})
      jdbc-opts)))

(defn delete-message [ds user-id message-id]
  (when (message-owned-by-user? ds message-id user-id)
    (let [result (jdbc/execute-one! (get-conn ds)
                   (sql/format {:delete-from :messages
                                :where [:= :id message-id]}))]
      (tel/log! {:level :info :data {:message-id message-id :user-id user-id}} "Message deleted")
      {:success (pos? (:next.jdbc/update-count result))})))

(defn update-message-annotation [ds user-id message-id annotation]
  (when (message-owned-by-user? ds message-id user-id)
    (jdbc/execute-one! (get-conn ds)
      (sql/format {:update :messages
                   :set {:annotation (or annotation "")}
                   :where [:and [:= :id message-id] (user-id-where-clause user-id)]
                   :returning [:id :annotation]})
      jdbc-opts)))
