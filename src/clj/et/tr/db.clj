(ns et.tr.db
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [et.tr.migrations :as migrations]
            [buddy.hashers :as hashers]
            [clojure.string :as str]
            [honey.sql :as sql]
            [taoensso.telemere :as tel]))

(def ^:private jdbc-opts {:builder-fn rs/as-unqualified-maps})

(declare associate-relations-with-items)

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

(def resource-select-columns [:id :title :link :description :tags :created_at :modified_at :sort_order :scope :importance])

(def meet-select-columns [:id :title :description :tags :created_at :modified_at :sort_order :scope :importance :start_date :start_time])

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
       (keep #(when-let [entry (lookup-map (:category_id %))]
                {:id (:category_id %) :name (:name entry) :badge_title (:badge_title entry)}))
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

(defn- build-search-clause
  ([search-term] (build-search-clause search-term [:title :tags]))
  ([search-term columns]
   (when (and search-term (not (str/blank? search-term)))
     (let [terms (->> (str/split (str/trim search-term) #"\s+")
                      (map str/lower-case)
                      (filter (complement str/blank?)))]
       (when (seq terms)
         (into [:and]
               (map (fn [term]
                      (into [:or]
                            (mapcat (fn [col]
                                      [[:like [:lower col] (str term "%")]
                                       [:like [:lower col] (str "% " term "%")]])
                                    columns)))
                    terms)))))))

(defn- build-category-subquery
  ([category-type category-names]
   (build-category-subquery :task_categories :task_id :tasks category-type category-names))
  ([join-table entity-id-col entity-ref category-type category-names]
   (when (seq category-names)
     (let [table-name (case category-type
                        "person" :people
                        "place" :places
                        "project" :projects
                        "goal" :goals)
           entity-ref-id (keyword (str (name entity-ref) ".id"))]
       [:exists {:select [1]
                 :from [join-table]
                 :join [[table-name] [:= (keyword (str (name table-name) ".id")) (keyword (str (name join-table) ".category_id"))]]
                 :where [:and
                         [:= (keyword (str (name join-table) "." (name entity-id-col))) entity-ref-id]
                         [:= (keyword (str (name join-table) ".category_type")) category-type]
                         [:in (keyword (str (name table-name) ".name")) category-names]]}]))))

(defn- build-category-clauses [categories]
  (let [people-clause (build-category-subquery "person" (:people categories))
        places-clause (build-category-subquery "place" (:places categories))
        projects-clause (build-category-subquery "project" (:projects categories))
        goals-clause (build-category-subquery "goal" (:goals categories))]
    (filterv some? [people-clause places-clause projects-clause goals-clause])))

(defn- build-exclusion-subquery
  ([category-type category-names]
   (build-exclusion-subquery :task_categories :task_id :tasks category-type category-names))
  ([join-table entity-id-col entity-ref category-type category-names]
   (when (seq category-names)
     (let [table-name (case category-type
                        "place" :places
                        "project" :projects)]
       [:not [:exists {:select [1]
                       :from [join-table]
                       :join [[table-name] [:= (keyword (str (name table-name) ".id")) (keyword (str (name join-table) ".category_id"))]]
                       :where [:and
                               [:= (keyword (str (name join-table) "." (name entity-id-col))) (keyword (str (name entity-ref) ".id"))]
                               [:= (keyword (str (name join-table) ".category_type")) category-type]
                               [:in (keyword (str (name table-name) ".name")) category-names]]}]]))))

(defn- fetch-category-lookups [conn user-id-where-clause]
  (let [cols [:id :name :badge_title]
        people (jdbc/execute! conn
                 (sql/format {:select cols
                              :from [:people]
                              :where user-id-where-clause})
                 jdbc-opts)
        places (jdbc/execute! conn
                 (sql/format {:select cols
                              :from [:places]
                              :where user-id-where-clause})
                 jdbc-opts)
        projects (jdbc/execute! conn
                   (sql/format {:select cols
                                :from [:projects]
                                :where user-id-where-clause})
                   jdbc-opts)
        goals (jdbc/execute! conn
                (sql/format {:select cols
                              :from [:goals]
                              :where user-id-where-clause})
                jdbc-opts)
        to-map (fn [items] (into {} (map (fn [i] [(:id i) (select-keys i [:name :badge_title])]) items)))]
    {:people-by-id (to-map people)
     :places-by-id (to-map places)
     :projects-by-id (to-map projects)
     :goals-by-id (to-map goals)}))

(defn- build-importance-clause [importance]
  (case importance
    "important" [:in :importance ["important" "critical"]]
    "critical" [:= :importance "critical"]
    nil))

(defn- build-scope-clause [context strict]
  (when context
    (if strict
      [:= :scope context]
      (case context
        "private" [:in :scope ["private" "both"]]
        "work" [:in :scope ["work" "both"]]
        nil))))

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
         importance-clause (build-importance-clause importance)
         scope-clause (build-scope-clause context strict)
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
         categories-by-task (group-by :task_id categories)
         tasks-with-categories (associate-categories-with-tasks tasks categories-by-task people-by-id places-by-id projects-by-id goals-by-id)]
     (associate-relations-with-items tasks-with-categories "tsk" conn))))

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
                              :returning [:id :name :tags :sort_order :badge_title]})
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
    (sql/format {:select [:id :name :description :tags :sort_order :badge_title]
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

(defn- update-category [ds user-id category-id name description tags badge-title table-name]
  (validate-table-name! table-name)
  (jdbc/execute-one! (get-conn ds)
    (sql/format {:update (keyword table-name)
                 :set {:name name :description description :tags tags :badge_title (or badge-title "")}
                 :where [:and [:= :id category-id] (user-id-where-clause user-id)]
                 :returning [:id :name :description :tags :badge_title]})
    jdbc-opts))

(defn update-person [ds user-id person-id name description tags badge-title]
  (update-category ds user-id person-id name description tags badge-title "people"))

(defn update-place [ds user-id place-id name description tags badge-title]
  (update-category ds user-id place-id name description tags badge-title "places"))

(defn update-project [ds user-id project-id name description tags badge-title]
  (update-category ds user-id project-id name description tags badge-title "projects"))

(defn update-goal [ds user-id goal-id name description tags badge-title]
  (update-category ds user-id goal-id name description tags badge-title "goals"))

(defn delete-category [ds user-id category-id category-type table-name]
  (validate-table-name! table-name)
  (validate-category-type! category-type)
  (let [conn (get-conn ds)
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
                                                     :where (user-id-where-clause user-id)}])})))
      (let [result (jdbc/execute-one! tx
                     (sql/format {:delete-from (keyword table-name)
                                  :where [:and [:= :id category-id] (user-id-where-clause user-id)]}))]
        (tel/log! {:level :info :data {:category table-name :id category-id :user-id user-id}} "Category deleted")
        {:success (pos? (:next.jdbc/update-count result))}))))

(defn get-task [ds user-id task-id]
  (let [conn (get-conn ds)
        user-where (user-id-where-clause user-id)
        task (jdbc/execute-one! conn
               (sql/format {:select task-select-columns
                            :from [:tasks]
                            :where [:and [:= :id task-id] user-where]})
               jdbc-opts)]
    (when task
      (let [categories (jdbc/execute! conn
                         (sql/format {:select [:task_id :category_type :category_id]
                                      :from [:task_categories]
                                      :where [:= :task_id task-id]})
                         jdbc-opts)
            {:keys [people-by-id places-by-id projects-by-id goals-by-id]} (fetch-category-lookups conn user-where)
            categories-by-task (group-by :task_id categories)]
        (first (associate-categories-with-tasks [task] categories-by-task people-by-id places-by-id projects-by-id goals-by-id))))))

(defn task-owned-by-user? [ds task-id user-id]
  (some? (jdbc/execute-one! (get-conn ds)
           (sql/format {:select [:id]
                        :from [:tasks]
                        :where [:and [:= :id task-id] (user-id-where-clause user-id)]})
           jdbc-opts)))

(defn- category-owned-by-user? [ds category-type category-id user-id]
  (let [table-name (case category-type
                     "person" :people
                     "place" :places
                     "project" :projects
                     "goal" :goals)]
    (some? (jdbc/execute-one! (get-conn ds)
             (sql/format {:select [:id]
                          :from [table-name]
                          :where [:and [:= :id category-id] (user-id-where-clause user-id)]})
             jdbc-opts))))

(defn categorize-task [ds user-id task-id category-type category-id]
  (validate-category-type! category-type)
  (when (and (task-owned-by-user? ds task-id user-id)
             (category-owned-by-user? ds category-type category-id user-id))
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
  (when (and (task-owned-by-user? ds task-id user-id)
             (category-owned-by-user? ds category-type category-id user-id))
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

(defn- associate-categories-with-resources-for-export [resources categories-by-resource people-by-id projects-by-id]
  (mapv (fn [resource]
          (let [resource-categories (get categories-by-resource (:id resource) [])]
            (assoc resource
                   :people (extract-category resource-categories "person" people-by-id)
                   :projects (extract-category resource-categories "project" projects-by-id))))
        resources))

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
                 (sql/format {:select [:id :name :description :sort_order :badge_title]
                              :from [:people]
                              :where user-where
                              :order-by [[:sort_order :asc] [:name :asc]]})
                 jdbc-opts)
        places (jdbc/execute! conn
                 (sql/format {:select [:id :name :description :sort_order :badge_title]
                              :from [:places]
                              :where user-where
                              :order-by [[:sort_order :asc] [:name :asc]]})
                 jdbc-opts)
        projects (jdbc/execute! conn
                   (sql/format {:select [:id :name :description :sort_order :badge_title]
                                :from [:projects]
                                :where user-where
                                :order-by [[:sort_order :asc] [:name :asc]]})
                   jdbc-opts)
        goals (jdbc/execute! conn
                (sql/format {:select [:id :name :description :sort_order :badge_title]
                             :from [:goals]
                             :where user-where
                             :order-by [[:sort_order :asc] [:name :asc]]})
                jdbc-opts)
        resources (jdbc/execute! conn
                    (sql/format {:select [:id :title :link :description :tags :created_at :modified_at :sort_order :scope :importance]
                                 :from [:resources]
                                 :where user-where
                                 :order-by [[:created_at :asc]]})
                    jdbc-opts)
        resource-ids (mapv :id resources)
        resource-categories (when (seq resource-ids)
                              (jdbc/execute! conn
                                (sql/format {:select [:resource_id :category_type :category_id]
                                             :from [:resource_categories]
                                             :where [:in :resource_id resource-ids]})
                                jdbc-opts))
        people-by-id (into {} (map (juxt :id :name) people))
        places-by-id (into {} (map (juxt :id :name) places))
        projects-by-id (into {} (map (juxt :id :name) projects))
        goals-by-id (into {} (map (juxt :id :name) goals))
        categories-by-task (group-by :task_id categories)
        categories-by-resource (group-by :resource_id resource-categories)
        tasks-with-categories (->> (associate-categories-with-tasks tasks categories-by-task people-by-id places-by-id projects-by-id goals-by-id)
                                   (mapv normalize-task))
        resources-with-categories (associate-categories-with-resources-for-export resources categories-by-resource people-by-id projects-by-id)]
    {:tasks tasks-with-categories
     :people people
     :places places
     :projects projects
     :goals goals
     :resources resources-with-categories}))

(defn add-message [ds user-id sender title description type]
  (let [result (jdbc/execute-one! (get-conn ds)
                 (sql/format {:insert-into :messages
                              :values [{:sender sender
                                        :title title
                                        :description (or description "")
                                        :type (when-not (str/blank? type) type)
                                        :user_id user-id}]
                              :returning [:id :sender :title :description :created_at :done :type :user_id]})
                 jdbc-opts)]
    (tel/log! {:level :info :data {:message-id (:id result) :user-id user-id}} "Message added")
    result))

(defn list-messages
  ([ds user-id] (list-messages ds user-id {}))
  ([ds user-id opts]
   (let [{:keys [sort-mode sender-filter excluded-senders]
          :or {sort-mode :recent}} opts
         user-where (user-id-where-clause user-id)
         done-filter (case sort-mode
                       :done [:= :done 1]
                       [:= :done 0])
         order-dir (if (= sort-mode :reverse) :asc :desc)
         where-clause (cond-> [:and user-where done-filter]
                        sender-filter (conj [:= :sender sender-filter])
                        (seq excluded-senders) (conj [:not-in :sender excluded-senders]))]
     (jdbc/execute! (get-conn ds)
       (sql/format {:select [:id :sender :title :description :created_at :done :annotation :type]
                    :from [:messages]
                    :where where-clause
                    :order-by [[:created_at order-dir]]})
       jdbc-opts))))

(defn message-owned-by-user? [ds message-id user-id]
  (some? (jdbc/execute-one! (get-conn ds)
           (sql/format {:select [:id]
                        :from [:messages]
                        :where [:and [:= :id message-id] (user-id-where-clause user-id)]})
           jdbc-opts)))

(defn get-message [ds user-id message-id]
  (jdbc/execute-one! (get-conn ds)
    (sql/format {:select [:id :sender :title :description :annotation]
                 :from [:messages]
                 :where [:and [:= :id message-id] (user-id-where-clause user-id)]})
    jdbc-opts))

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

(defn reset-all-data! [ds]
  (let [conn (get-conn ds)]
    (doseq [table [:relations :task_categories :resource_categories :meet_categories :tasks :messages :resources :meets :people :places :projects :goals :users]]
      (jdbc/execute-one! conn (sql/format {:delete-from table})))))

(defn add-resource [ds user-id title link scope]
  (let [conn (get-conn ds)
        valid-scope (normalize-scope scope)
        min-order (or (:min_order (jdbc/execute-one! conn
                                    (sql/format {:select [[[:min :sort_order] :min_order]]
                                                 :from [:resources]
                                                 :where (user-id-where-clause user-id)})
                                    jdbc-opts))
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
                              :returning (conj resource-select-columns :user_id)})
                 jdbc-opts)]
    (tel/log! {:level :info :data {:resource-id (:id result) :user-id user-id}} "Resource added")
    (assoc result :people [] :places [] :projects [])))

(defn- build-resource-category-clauses [categories]
  (let [people-clause (build-category-subquery :resource_categories :resource_id :resources "person" (:people categories))
        places-clause (build-category-subquery :resource_categories :resource_id :resources "place" (:places categories))
        projects-clause (build-category-subquery :resource_categories :resource_id :resources "project" (:projects categories))]
    (filterv some? [people-clause places-clause projects-clause])))

(defn- associate-categories-with-resources [resources categories-by-resource people-by-id places-by-id projects-by-id]
  (mapv (fn [resource]
          (let [resource-categories (get categories-by-resource (:id resource) [])]
            (assoc resource
                   :people (extract-category resource-categories "person" people-by-id)
                   :places (extract-category resource-categories "place" places-by-id)
                   :projects (extract-category resource-categories "project" projects-by-id))))
        resources))

(defn list-resources
  ([ds user-id] (list-resources ds user-id {}))
  ([ds user-id opts]
   (let [{:keys [search-term importance context strict categories]} opts
         conn (get-conn ds)
         user-where (user-id-where-clause user-id)
         search-clause (build-search-clause search-term [:title :tags :link])
         importance-clause (build-importance-clause importance)
         scope-clause (build-scope-clause context strict)
         category-clauses (build-resource-category-clauses categories)
         where-clause (into [:and user-where]
                            (concat (filter some? [search-clause importance-clause scope-clause])
                                    category-clauses))
         resources (jdbc/execute! conn
                     (sql/format {:select resource-select-columns
                                  :from [:resources]
                                  :where where-clause
                                  :order-by [[:modified_at :desc]]})
                     jdbc-opts)
         resource-ids (mapv :id resources)
         categories-data (when (seq resource-ids)
                           (jdbc/execute! conn
                             (sql/format {:select [:resource_id :category_type :category_id]
                                          :from [:resource_categories]
                                          :where [:in :resource_id resource-ids]})
                             jdbc-opts))
         {:keys [people-by-id places-by-id projects-by-id]} (fetch-category-lookups conn user-where)
         categories-by-resource (group-by :resource_id categories-data)
         resources-with-categories (associate-categories-with-resources resources categories-by-resource people-by-id places-by-id projects-by-id)]
     (associate-relations-with-items resources-with-categories "res" conn))))

(defn resource-owned-by-user? [ds resource-id user-id]
  (some? (jdbc/execute-one! (get-conn ds)
           (sql/format {:select [:id]
                        :from [:resources]
                        :where [:and [:= :id resource-id] (user-id-where-clause user-id)]})
           jdbc-opts)))

(defn get-resource [ds user-id resource-id]
  (let [conn (get-conn ds)
        user-where (user-id-where-clause user-id)
        resource (jdbc/execute-one! conn
                   (sql/format {:select resource-select-columns
                                :from [:resources]
                                :where [:and [:= :id resource-id] user-where]})
                   jdbc-opts)]
    (when resource
      (let [categories-data (jdbc/execute! conn
                              (sql/format {:select [:resource_id :category_type :category_id]
                                           :from [:resource_categories]
                                           :where [:= :resource_id resource-id]})
                              jdbc-opts)
            {:keys [people-by-id places-by-id projects-by-id]} (fetch-category-lookups conn user-where)
            categories-by-resource (group-by :resource_id categories-data)]
        (first (associate-categories-with-resources [resource] categories-by-resource people-by-id places-by-id projects-by-id))))))

(defn update-resource [ds user-id resource-id fields]
  (let [field-names (keys fields)
        set-map (assoc fields :modified_at [:raw "datetime('now')"])
        return-cols (into [:id :created_at :modified_at] field-names)]
    (jdbc/execute-one! (get-conn ds)
      (sql/format {:update :resources
                   :set set-map
                   :where [:and [:= :id resource-id] (user-id-where-clause user-id)]
                   :returning return-cols})
      jdbc-opts)))

(defn convert-message-to-resource [ds user-id message-id link]
  (let [conn (get-conn ds)]
    (jdbc/with-transaction [tx conn]
      (when-let [message (jdbc/execute-one! tx
                           (sql/format {:select [:id :sender :title :description :annotation]
                                        :from [:messages]
                                        :where [:and [:= :id message-id] (user-id-where-clause user-id)]})
                           jdbc-opts)]
        (let [description (str (or (:description message) "")
                              (when (and (seq (:description message)) (seq (:annotation message))) "\n")
                              (or (:annotation message) ""))
              min-order (or (:min_order (jdbc/execute-one! tx
                                          (sql/format {:select [[[:min :sort_order] :min_order]]
                                                       :from [:resources]
                                                       :where (user-id-where-clause user-id)})
                                          jdbc-opts))
                            1.0)
              new-order (- min-order 1.0)
              resource (jdbc/execute-one! tx
                         (sql/format {:insert-into :resources
                                      :values [{:title (:title message)
                                                :link link
                                                :sort_order new-order
                                                :user_id user-id
                                                :modified_at [:raw "datetime('now')"]
                                                :scope "both"}]
                                      :returning (conj resource-select-columns :user_id)})
                         jdbc-opts)]
          (when (seq description)
            (jdbc/execute-one! tx
              (sql/format {:update :resources
                           :set {:description description :modified_at [:raw "datetime('now')"]}
                           :where [:and [:= :id (:id resource)] (user-id-where-clause user-id)]})
              jdbc-opts))
          (jdbc/execute-one! tx
            (sql/format {:delete-from :messages
                         :where [:= :id message-id]}))
          (tel/log! {:level :info :data {:message-id message-id :resource-id (:id resource) :user-id user-id}} "Message converted to resource")
          (assoc resource :description description :people [] :projects []))))))

(defn delete-resource [ds user-id resource-id]
  (when (resource-owned-by-user? ds resource-id user-id)
    (let [conn (get-conn ds)]
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
  (let [normalize-fn (get field-normalizers field identity)
        valid-value (normalize-fn value)]
    (jdbc/execute-one! (get-conn ds)
      (sql/format {:update :resources
                   :set {field valid-value
                         :modified_at [:raw "datetime('now')"]}
                   :where [:and [:= :id resource-id] (user-id-where-clause user-id)]
                   :returning [:id field :modified_at]})
      jdbc-opts)))

(defn categorize-resource [ds user-id resource-id category-type category-id]
  (validate-category-type! category-type)
  (when (and (resource-owned-by-user? ds resource-id user-id)
             (category-owned-by-user? ds category-type category-id user-id))
    (let [conn (get-conn ds)]
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
  (validate-category-type! category-type)
  (when (and (resource-owned-by-user? ds resource-id user-id)
             (category-owned-by-user? ds category-type category-id user-id))
    (let [conn (get-conn ds)]
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

(defn add-meet
  ([ds user-id title] (add-meet ds user-id title "both"))
  ([ds user-id title scope]
   (let [conn (get-conn ds)
         valid-scope (normalize-scope scope)
         min-order (or (:min_order (jdbc/execute-one! conn
                                     (sql/format {:select [[[:min :sort_order] :min_order]]
                                                  :from [:meets]
                                                  :where (user-id-where-clause user-id)})
                                     jdbc-opts))
                       1.0)
         new-order (- min-order 1.0)
         result (jdbc/execute-one! conn
                  (sql/format {:insert-into :meets
                               :values [{:title title
                                         :sort_order new-order
                                         :user_id user-id
                                         :modified_at [:raw "datetime('now')"]
                                         :start_date [:raw "date('now','localtime')"]
                                         :start_time [:raw "strftime('%H:%M','now','localtime')"]
                                         :scope valid-scope}]
                               :returning (conj meet-select-columns :user_id)})
                  jdbc-opts)]
     (tel/log! {:level :info :data {:meet-id (:id result) :user-id user-id}} "Meet added")
     (assoc result :people [] :places [] :projects []))))

(defn- build-meet-category-clauses [categories]
  (let [people-clause (build-category-subquery :meet_categories :meet_id :meets "person" (:people categories))
        places-clause (build-category-subquery :meet_categories :meet_id :meets "place" (:places categories))
        projects-clause (build-category-subquery :meet_categories :meet_id :meets "project" (:projects categories))]
    (filterv some? [people-clause places-clause projects-clause])))

(defn- associate-categories-with-meets [meets categories-by-meet people-by-id places-by-id projects-by-id]
  (mapv (fn [meet]
          (let [meet-categories (get categories-by-meet (:id meet) [])]
            (assoc meet
                   :people (extract-category meet-categories "person" people-by-id)
                   :places (extract-category meet-categories "place" places-by-id)
                   :projects (extract-category meet-categories "project" projects-by-id))))
        meets))

(defn list-meets
  ([ds user-id] (list-meets ds user-id {}))
  ([ds user-id opts]
   (let [{:keys [search-term importance context strict categories sort-mode excluded-places excluded-projects]} opts
         conn (get-conn ds)
         user-where (user-id-where-clause user-id)
         date-clause (case sort-mode
                       :past [:< :start_date [:raw "date('now','localtime')"]]
                       [:>= :start_date [:raw "date('now','localtime')"]])
         search-clause (build-search-clause search-term [:title :tags])
         importance-clause (build-importance-clause importance)
         scope-clause (build-scope-clause context strict)
         category-clauses (build-meet-category-clauses categories)
         exclusion-clauses (filterv some? [(build-exclusion-subquery :meet_categories :meet_id :meets "place" excluded-places)
                                           (build-exclusion-subquery :meet_categories :meet_id :meets "project" excluded-projects)])
         where-clause (into [:and user-where date-clause]
                            (concat (filter some? [search-clause importance-clause scope-clause])
                                    category-clauses
                                    exclusion-clauses))
         order-by (case sort-mode
                    :past [[:start_date :desc] [:start_time :desc]]
                    [[:start_date :asc] [:start_time :asc]])
         meets (jdbc/execute! conn
                 (sql/format {:select meet-select-columns
                              :from [:meets]
                              :where where-clause
                              :order-by order-by})
                 jdbc-opts)
         meet-ids (mapv :id meets)
         categories-data (when (seq meet-ids)
                           (jdbc/execute! conn
                             (sql/format {:select [:meet_id :category_type :category_id]
                                          :from [:meet_categories]
                                          :where [:in :meet_id meet-ids]})
                             jdbc-opts))
         {:keys [people-by-id places-by-id projects-by-id]} (fetch-category-lookups conn user-where)
         categories-by-meet (group-by :meet_id categories-data)
         meets-with-categories (associate-categories-with-meets meets categories-by-meet people-by-id places-by-id projects-by-id)]
     (associate-relations-with-items meets-with-categories "met" conn))))

(defn meet-owned-by-user? [ds meet-id user-id]
  (some? (jdbc/execute-one! (get-conn ds)
           (sql/format {:select [:id]
                        :from [:meets]
                        :where [:and [:= :id meet-id] (user-id-where-clause user-id)]})
           jdbc-opts)))

(defn get-meet [ds user-id meet-id]
  (let [conn (get-conn ds)
        user-where (user-id-where-clause user-id)
        meet (jdbc/execute-one! conn
               (sql/format {:select meet-select-columns
                            :from [:meets]
                            :where [:and [:= :id meet-id] user-where]})
               jdbc-opts)]
    (when meet
      (let [categories-data (jdbc/execute! conn
                              (sql/format {:select [:meet_id :category_type :category_id]
                                           :from [:meet_categories]
                                           :where [:= :meet_id meet-id]})
                              jdbc-opts)
            {:keys [people-by-id places-by-id projects-by-id]} (fetch-category-lookups conn user-where)
            categories-by-meet (group-by :meet_id categories-data)]
        (first (associate-categories-with-meets [meet] categories-by-meet people-by-id places-by-id projects-by-id))))))

(defn update-meet [ds user-id meet-id fields]
  (let [set-map (assoc fields :modified_at [:raw "datetime('now')"])
        return-cols (into [:id :created_at :modified_at] (keys fields))]
    (jdbc/execute-one! (get-conn ds)
      (sql/format {:update :meets
                   :set set-map
                   :where [:and [:= :id meet-id] (user-id-where-clause user-id)]
                   :returning return-cols})
      jdbc-opts)))

(defn delete-meet [ds user-id meet-id]
  (when (meet-owned-by-user? ds meet-id user-id)
    (let [conn (get-conn ds)]
      (jdbc/with-transaction [tx conn]
        (jdbc/execute-one! tx
          (sql/format {:delete-from :meet_categories
                       :where [:= :meet_id meet-id]}))
        (jdbc/execute-one! tx
          (sql/format {:delete-from :relations
                       :where [:or
                               [:and [:= :source_type "met"] [:= :source_id meet-id]]
                               [:and [:= :target_type "met"] [:= :target_id meet-id]]]}))
        (let [result (jdbc/execute-one! tx
                       (sql/format {:delete-from :meets
                                    :where [:= :id meet-id]}))]
          (tel/log! {:level :info :data {:meet-id meet-id :user-id user-id}} "Meet deleted")
          {:success (pos? (:next.jdbc/update-count result))})))))

(defn set-meet-field [ds user-id meet-id field value]
  (let [normalize-fn (get field-normalizers field identity)
        valid-value (normalize-fn value)]
    (jdbc/execute-one! (get-conn ds)
      (sql/format {:update :meets
                   :set {field valid-value
                         :modified_at [:raw "datetime('now')"]}
                   :where [:and [:= :id meet-id] (user-id-where-clause user-id)]
                   :returning [:id field :modified_at]})
      jdbc-opts)))

(defn set-meet-start-date [ds user-id meet-id start-date]
  (jdbc/execute-one! (get-conn ds)
    (sql/format {:update :meets
                 :set {:start_date start-date
                       :modified_at [:raw "datetime('now')"]}
                 :where [:and [:= :id meet-id] (user-id-where-clause user-id)]
                 :returning [:id :start_date :start_time :modified_at]})
    jdbc-opts))

(defn set-meet-start-time [ds user-id meet-id start-time]
  (let [normalized-time (if (empty? start-time) nil start-time)]
    (jdbc/execute-one! (get-conn ds)
      (sql/format {:update :meets
                   :set {:start_time normalized-time
                         :modified_at [:raw "datetime('now')"]}
                   :where [:and [:= :id meet-id] (user-id-where-clause user-id)]
                   :returning [:id :start_date :start_time :modified_at]})
      jdbc-opts)))

(defn categorize-meet [ds user-id meet-id category-type category-id]
  (validate-category-type! category-type)
  (when (and (meet-owned-by-user? ds meet-id user-id)
             (category-owned-by-user? ds category-type category-id user-id))
    (let [conn (get-conn ds)]
      (jdbc/with-transaction [tx conn]
        (jdbc/execute-one! tx
          (sql/format {:insert-into :meet_categories
                       :values [{:meet_id meet-id
                                 :category_type category-type
                                 :category_id category-id}]
                       :on-conflict []
                       :do-nothing true}))
        (jdbc/execute-one! tx
          (sql/format {:update :meets
                       :set {:modified_at [:raw "datetime('now')"]}
                       :where [:= :id meet-id]}))))))

(defn uncategorize-meet [ds user-id meet-id category-type category-id]
  (validate-category-type! category-type)
  (when (and (meet-owned-by-user? ds meet-id user-id)
             (category-owned-by-user? ds category-type category-id user-id))
    (let [conn (get-conn ds)]
      (jdbc/with-transaction [tx conn]
        (jdbc/execute-one! tx
          (sql/format {:delete-from :meet_categories
                       :where [:and
                               [:= :meet_id meet-id]
                               [:= :category_type category-type]
                               [:= :category_id category-id]]}))
        (jdbc/execute-one! tx
          (sql/format {:update :meets
                       :set {:modified_at [:raw "datetime('now')"]}
                       :where [:= :id meet-id]}))))))

(def ^:private valid-relation-types #{"tsk" "res" "met"})

(defn- validate-relation-type! [type]
  (when-not (contains? valid-relation-types type)
    (throw (ex-info "Invalid relation type" {:type type}))))

(defn- item-exists? [ds user-id type id]
  (let [conn (get-conn ds)
        table (case type "tsk" :tasks "res" :resources "met" :meets)
        user-where (user-id-where-clause user-id)]
    (some? (jdbc/execute-one! conn
             (sql/format {:select [:id]
                          :from [table]
                          :where [:and [:= :id id] user-where]})
             jdbc-opts))))

(defn add-relation [ds user-id source-type source-id target-type target-id]
  (validate-relation-type! source-type)
  (validate-relation-type! target-type)
  (when (and (item-exists? ds user-id source-type source-id)
             (item-exists? ds user-id target-type target-id))
    (let [conn (get-conn ds)]
      (jdbc/with-transaction [tx conn]
        (jdbc/execute-one! tx
          (sql/format {:insert-into :relations
                       :values [{:source_type source-type
                                 :source_id source-id
                                 :target_type target-type
                                 :target_id target-id}]
                       :on-conflict []
                       :do-nothing true}))
        (jdbc/execute-one! tx
          (sql/format {:insert-into :relations
                       :values [{:source_type target-type
                                 :source_id target-id
                                 :target_type source-type
                                 :target_id source-id}]
                       :on-conflict []
                       :do-nothing true})))
      (tel/log! {:level :info :data {:source-type source-type :source-id source-id
                                      :target-type target-type :target-id target-id
                                      :user-id user-id}} "Relation added")
      {:success true})))

(defn delete-relation [ds user-id source-type source-id target-type target-id]
  (validate-relation-type! source-type)
  (validate-relation-type! target-type)
  (when (and (item-exists? ds user-id source-type source-id)
             (item-exists? ds user-id target-type target-id))
    (let [conn (get-conn ds)]
      (jdbc/with-transaction [tx conn]
        (jdbc/execute-one! tx
          (sql/format {:delete-from :relations
                       :where [:and
                               [:= :source_type source-type]
                               [:= :source_id source-id]
                               [:= :target_type target-type]
                               [:= :target_id target-id]]}))
        (jdbc/execute-one! tx
          (sql/format {:delete-from :relations
                       :where [:and
                               [:= :source_type target-type]
                               [:= :source_id target-id]
                               [:= :target_type source-type]
                               [:= :target_id source-id]]})))
      (tel/log! {:level :info :data {:source-type source-type :source-id source-id
                                      :target-type target-type :target-id target-id
                                      :user-id user-id}} "Relation deleted")
      {:success true})))

(defn get-relations-for-item [ds user-id source-type source-id]
  (validate-relation-type! source-type)
  (when (item-exists? ds user-id source-type source-id)
    (jdbc/execute! (get-conn ds)
      (sql/format {:select [:target_type :target_id]
                   :from [:relations]
                   :where [:and
                           [:= :source_type source-type]
                           [:= :source_id source-id]]})
      jdbc-opts)))

(defn- fetch-title-for-relation [conn type id]
  (let [table (case type "tsk" :tasks "res" :resources "met" :meets)]
    (:title (jdbc/execute-one! conn
              (sql/format {:select [:title]
                           :from [table]
                           :where [:= :id id]})
              jdbc-opts))))

(defn get-relations-with-titles [ds user-id source-type source-id]
  (when-let [relations (get-relations-for-item ds user-id source-type source-id)]
    (let [conn (get-conn ds)]
      (mapv (fn [{:keys [target_type target_id]}]
              {:type target_type
               :id target_id
               :title (fetch-title-for-relation conn target_type target_id)})
            relations))))

(defn- fetch-relations-batch [conn source-type source-ids]
  (when (seq source-ids)
    (jdbc/execute! conn
      (sql/format {:select [:source_id :target_type :target_id]
                   :from [:relations]
                   :where [:and
                           [:= :source_type source-type]
                           [:in :source_id source-ids]]})
      jdbc-opts)))

(defn- enrich-relations-with-titles [conn relations]
  (let [grouped (group-by :target_type relations)
        title-maps (into {}
                         (for [[type rels] grouped
                               :let [ids (mapv :target_id rels)
                                     table (case type "tsk" :tasks "res" :resources "met" :meets)
                                     items (jdbc/execute! conn
                                             (sql/format {:select [:id :title]
                                                          :from [table]
                                                          :where [:in :id ids]})
                                             jdbc-opts)]]
                           [type (into {} (map (juxt :id :title) items))]))]
    (mapv (fn [{:keys [target_type target_id] :as rel}]
            (assoc rel :title (get-in title-maps [target_type target_id])))
          relations)))

(defn associate-relations-with-items [items source-type conn]
  (let [item-ids (mapv :id items)
        relations (fetch-relations-batch conn source-type item-ids)
        enriched (enrich-relations-with-titles conn relations)
        relations-by-source (group-by :source_id enriched)]
    (mapv (fn [item]
            (let [item-relations (get relations-by-source (:id item) [])]
              (assoc item :relations
                     (mapv (fn [{:keys [target_type target_id title]}]
                             {:type target_type :id target_id :title title})
                           item-relations))))
          items)))

(defn delete-relations-for-item [conn source-type source-id]
  (jdbc/execute-one! conn
    (sql/format {:delete-from :relations
                 :where [:or
                         [:and [:= :source_type source-type] [:= :source_id source-id]]
                         [:and [:= :target_type source-type] [:= :target_id source-id]]]})))
