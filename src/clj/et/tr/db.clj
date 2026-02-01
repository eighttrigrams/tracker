(ns et.tr.db
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [et.tr.migrations :as migrations]
            [buddy.hashers :as hashers]
            [clojure.string]
            [honey.sql :as sql]))

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
  (let [hash (hashers/derive password)]
    (jdbc/execute-one! (get-conn ds)
      ["INSERT INTO users (username, password_hash) VALUES (?, ?) RETURNING id, username, language, created_at"
       username hash]
      {:builder-fn rs/as-unqualified-maps})))

(defn get-user-by-username [ds username]
  (jdbc/execute-one! (get-conn ds)
    ["SELECT id, username, password_hash, language, created_at FROM users WHERE username = ?" username]
    {:builder-fn rs/as-unqualified-maps}))

(defn verify-user [ds username password]
  (when-let [user (get-user-by-username ds username)]
    (when (hashers/check password (:password_hash user))
      (dissoc user :password_hash))))

(defn list-users [ds]
  (jdbc/execute! (get-conn ds)
    ["SELECT id, username, language, created_at FROM users WHERE username != 'admin' ORDER BY created_at"]
    {:builder-fn rs/as-unqualified-maps}))

(defn delete-user [ds user-id]
  (let [conn (get-conn ds)
        task-ids (mapv :id (jdbc/execute! conn
                             ["SELECT id FROM tasks WHERE user_id = ?" user-id]
                             {:builder-fn rs/as-unqualified-maps}))]
    (when (seq task-ids)
      (jdbc/execute-one! conn
        (into [(str "DELETE FROM task_categories WHERE task_id IN ("
                    (clojure.string/join "," (repeat (count task-ids) "?"))
                    ")")] task-ids)))
    (jdbc/execute-one! conn ["DELETE FROM tasks WHERE user_id = ?" user-id])
    (jdbc/execute-one! conn ["DELETE FROM messages WHERE user_id = ?" user-id])
    (jdbc/execute-one! conn ["DELETE FROM people WHERE user_id = ?" user-id])
    (jdbc/execute-one! conn ["DELETE FROM places WHERE user_id = ?" user-id])
    (jdbc/execute-one! conn ["DELETE FROM projects WHERE user_id = ?" user-id])
    (jdbc/execute-one! conn ["DELETE FROM goals WHERE user_id = ?" user-id])
    (let [result (jdbc/execute-one! conn ["DELETE FROM users WHERE id = ?" user-id])]
      {:success (pos? (:next.jdbc/update-count result))})))

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

(defn- user-id-clause [user-id]
  {:clause (if user-id "user_id = ?" "user_id IS NULL")
   :params (if user-id [user-id] [])})

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
                                     ["SELECT MIN(sort_order) as min_order FROM tasks WHERE user_id = ? OR (user_id IS NULL AND ? IS NULL)"
                                      user-id user-id]
                                     {:builder-fn rs/as-unqualified-maps}))
                       1.0)
         new-order (- min-order 1.0)
         result (jdbc/execute-one! conn
                  [(str "INSERT INTO tasks (title, sort_order, user_id, modified_at, scope) VALUES (?, ?, ?, datetime('now'), ?) RETURNING " (clojure.string/join ", " (map name task-select-columns)) ", user_id")
                   title new-order user-id valid-scope]
                  {:builder-fn rs/as-unqualified-maps})]
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

(defn- fetch-category-lookups [conn user-id-where-clause]
  (let [people (jdbc/execute! conn
                 (sql/format {:select [:id :name]
                              :from [:people]
                              :where user-id-where-clause})
                 {:builder-fn rs/as-unqualified-maps})
        places (jdbc/execute! conn
                 (sql/format {:select [:id :name]
                              :from [:places]
                              :where user-id-where-clause})
                 {:builder-fn rs/as-unqualified-maps})
        projects (jdbc/execute! conn
                   (sql/format {:select [:id :name]
                                :from [:projects]
                                :where user-id-where-clause})
                   {:builder-fn rs/as-unqualified-maps})
        goals (jdbc/execute! conn
                (sql/format {:select [:id :name]
                              :from [:goals]
                              :where user-id-where-clause})
                {:builder-fn rs/as-unqualified-maps})]
    {:people-by-id (into {} (map (juxt :id :name) people))
     :places-by-id (into {} (map (juxt :id :name) places))
     :projects-by-id (into {} (map (juxt :id :name) projects))
     :goals-by-id (into {} (map (juxt :id :name) goals))}))

(defn list-tasks
  ([ds user-id] (list-tasks ds user-id :recent))
  ([ds user-id sort-mode] (list-tasks ds user-id sort-mode nil))
  ([ds user-id sort-mode opts]
   (let [opts (if (string? opts) {:search-term opts} opts)
         {:keys [search-term importance context strict]} opts
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
         where-clause (into [:and base-where]
                            (filter some? [search-clause importance-clause scope-clause]))
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
                 {:builder-fn rs/as-unqualified-maps})
         task-ids (mapv :id tasks)
         categories (when (seq task-ids)
                      (jdbc/execute! conn
                        (sql/format {:select [:task_id :category_type :category_id]
                                     :from [:task_categories]
                                     :where [:in :task_id task-ids]})
                        {:builder-fn rs/as-unqualified-maps}))
         {:keys [people-by-id places-by-id projects-by-id goals-by-id]} (fetch-category-lookups conn user-where)
         categories-by-task (group-by :task_id categories)]
     (associate-categories-with-tasks tasks categories-by-task people-by-id places-by-id projects-by-id goals-by-id))))

(def ^:private valid-category-tables #{"people" "places" "projects" "goals"})

(defn- validate-table-name! [table-name]
  (when-not (contains? valid-category-tables table-name)
    (throw (ex-info "Invalid table name" {:table-name table-name}))))

(defn- add-category [ds user-id name table-name]
  (validate-table-name! table-name)
  (let [conn (get-conn ds)
        {:keys [clause params]} (user-id-clause user-id)
        max-order (or (:max_order (jdbc/execute-one! conn
                                    (into [(str "SELECT MAX(sort_order) as max_order FROM " table-name " WHERE " clause)] params)
                                    {:builder-fn rs/as-unqualified-maps}))
                      0)
        new-order (+ max-order 1.0)]
    (jdbc/execute-one! conn
      (into [(str "INSERT INTO " table-name " (name, user_id, sort_order) VALUES (?, ?, ?) RETURNING id, name, sort_order")] [name user-id new-order])
      {:builder-fn rs/as-unqualified-maps})))

(defn add-person [ds user-id name]
  (add-category ds user-id name "people"))

(defn add-place [ds user-id name]
  (add-category ds user-id name "places"))

(defn- list-category [ds user-id table-name]
  (validate-table-name! table-name)
  (let [{:keys [clause params]} (user-id-clause user-id)]
    (jdbc/execute! (get-conn ds)
      (into [(str "SELECT id, name, description, sort_order FROM " table-name " WHERE " clause " ORDER BY sort_order ASC, name ASC")] params)
      {:builder-fn rs/as-unqualified-maps})))

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
  (let [{:keys [clause params]} (user-id-clause user-id)
        query-params (concat [name description category-id] params)]
    (jdbc/execute-one! (get-conn ds)
      (into [(str "UPDATE " table-name " SET name = ?, description = ? WHERE id = ? AND " clause " RETURNING id, name, description")] query-params)
      {:builder-fn rs/as-unqualified-maps})))

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
  (let [{:keys [clause params]} (user-id-clause user-id)
        query-params (concat [category-id] params)
        conn (get-conn ds)]
    (jdbc/with-transaction [tx conn]
      (jdbc/execute-one! tx
        (into [(str "DELETE FROM task_categories WHERE category_type = ? AND category_id = ? "
                    "AND task_id IN (SELECT id FROM tasks WHERE " clause ")")
               category-type category-id] params))
      (let [result (jdbc/execute-one! tx
                     (into [(str "DELETE FROM " table-name " WHERE id = ? AND " clause)] query-params))]
        {:success (pos? (:next.jdbc/update-count result))}))))

(defn task-owned-by-user? [ds task-id user-id]
  (let [{:keys [clause params]} (user-id-clause user-id)
        query-params (concat [task-id] params)]
    (some? (jdbc/execute-one! (get-conn ds)
             (into [(str "SELECT id FROM tasks WHERE id = ? AND " clause)] query-params)
             {:builder-fn rs/as-unqualified-maps}))))

(defn categorize-task [ds user-id task-id category-type category-id]
  (when (task-owned-by-user? ds task-id user-id)
    (let [conn (get-conn ds)]
      (jdbc/execute-one! conn
        ["INSERT OR IGNORE INTO task_categories (task_id, category_type, category_id) VALUES (?, ?, ?)"
         task-id category-type category-id])
      (jdbc/execute-one! conn
        ["UPDATE tasks SET modified_at = datetime('now') WHERE id = ?" task-id]))))

(defn uncategorize-task [ds user-id task-id category-type category-id]
  (when (task-owned-by-user? ds task-id user-id)
    (let [conn (get-conn ds)]
      (jdbc/execute-one! conn
        ["DELETE FROM task_categories WHERE task_id = ? AND category_type = ? AND category_id = ?"
         task-id category-type category-id])
      (jdbc/execute-one! conn
        ["UPDATE tasks SET modified_at = datetime('now') WHERE id = ?" task-id]))))

(defn update-task [ds user-id task-id fields]
  (let [{:keys [clause params]} (user-id-clause user-id)
        field-names (keys fields)
        set-clause (clojure.string/join ", " (map #(str (name %) " = ?") field-names))
        query-params (concat (vals fields) [task-id] params)
        return-cols (clojure.string/join ", " (map name field-names))]
    (jdbc/execute-one! (get-conn ds)
      (into [(str "UPDATE tasks SET " set-clause ", modified_at = datetime('now') WHERE id = ? AND " clause " RETURNING id, " return-cols ", created_at, modified_at")] query-params)
      {:builder-fn rs/as-unqualified-maps})))

(defn get-task-sort-order [ds user-id task-id]
  (let [{:keys [clause params]} (user-id-clause user-id)
        query-params (concat [task-id] params)]
    (:sort_order (jdbc/execute-one! (get-conn ds)
                   (into [(str "SELECT sort_order FROM tasks WHERE id = ? AND " clause)] query-params)
                   {:builder-fn rs/as-unqualified-maps}))))

(defn reorder-task [ds user-id task-id new-sort-order]
  (let [{:keys [clause params]} (user-id-clause user-id)
        query-params (concat [new-sort-order task-id] params)]
    (jdbc/execute-one! (get-conn ds)
      (into [(str "UPDATE tasks SET sort_order = ? WHERE id = ? AND " clause)] query-params))
    {:success true :sort_order new-sort-order}))

(defn get-category-sort-order [ds user-id category-id table-name]
  (validate-table-name! table-name)
  (let [{:keys [clause params]} (user-id-clause user-id)
        query-params (concat [category-id] params)]
    (:sort_order (jdbc/execute-one! (get-conn ds)
                   (into [(str "SELECT sort_order FROM " table-name " WHERE id = ? AND " clause)] query-params)
                   {:builder-fn rs/as-unqualified-maps}))))

(defn reorder-category [ds user-id category-id new-sort-order table-name]
  (validate-table-name! table-name)
  (let [{:keys [clause params]} (user-id-clause user-id)
        query-params (concat [new-sort-order category-id] params)]
    (jdbc/execute-one! (get-conn ds)
      (into [(str "UPDATE " table-name " SET sort_order = ? WHERE id = ? AND " clause)] query-params))
    {:success true :sort_order new-sort-order}))

(defn set-task-due-date [ds user-id task-id due-date]
  (let [{:keys [clause params]} (user-id-clause user-id)
        update-clause (if (nil? due-date)
                        "UPDATE tasks SET due_date = ?, due_time = NULL, modified_at = datetime('now')"
                        "UPDATE tasks SET due_date = ?, modified_at = datetime('now')")
        query-params (concat [due-date task-id] params)]
    (jdbc/execute-one! (get-conn ds)
      (into [(str update-clause " WHERE id = ? AND " clause " RETURNING id, due_date, due_time, modified_at")] query-params)
      {:builder-fn rs/as-unqualified-maps})))

(defn set-task-due-time [ds user-id task-id due-time]
  (let [{:keys [clause params]} (user-id-clause user-id)
        normalized-time (if (empty? due-time) nil due-time)
        query-params (concat [normalized-time task-id] params)]
    (jdbc/execute-one! (get-conn ds)
      (into [(str "UPDATE tasks SET due_time = ?, modified_at = datetime('now') WHERE id = ? AND " clause " RETURNING id, due_date, due_time, modified_at")] query-params)
      {:builder-fn rs/as-unqualified-maps})))

(defn delete-task [ds user-id task-id]
  (when (task-owned-by-user? ds task-id user-id)
    (jdbc/execute-one! (get-conn ds)
      ["DELETE FROM task_categories WHERE task_id = ?" task-id])
    (let [result (jdbc/execute-one! (get-conn ds)
                   ["DELETE FROM tasks WHERE id = ?" task-id])]
      {:success (pos? (:next.jdbc/update-count result))})))

(defn set-task-done [ds user-id task-id done?]
  (let [{:keys [clause params]} (user-id-clause user-id)
        done-val (if done? 1 0)
        query-params (concat [done-val task-id] params)]
    (jdbc/execute-one! (get-conn ds)
      (into [(str "UPDATE tasks SET done = ?, modified_at = datetime('now') WHERE id = ? AND " clause " RETURNING id, done, modified_at")] query-params)
      {:builder-fn rs/as-unqualified-maps})))

(def ^:private field-normalizers
  {:scope normalize-scope
   :importance normalize-importance
   :urgency normalize-urgency})

(defn set-task-field [ds user-id task-id field value]
  (let [{:keys [clause params]} (user-id-clause user-id)
        normalize-fn (get field-normalizers field identity)
        valid-value (normalize-fn value)
        field-name (name field)
        query-params (concat [valid-value task-id] params)]
    (jdbc/execute-one! (get-conn ds)
      (into [(str "UPDATE tasks SET " field-name " = ?, modified_at = datetime('now') WHERE id = ? AND " clause " RETURNING id, " field-name ", modified_at")] query-params)
      {:builder-fn rs/as-unqualified-maps})))

(def valid-languages #{"en" "de" "pt"})

(defn get-user-language [ds user-id]
  (when user-id
    (:language (jdbc/execute-one! (get-conn ds)
                 ["SELECT language FROM users WHERE id = ?" user-id]
                 {:builder-fn rs/as-unqualified-maps}))))

(defn set-user-language [ds user-id language]
  (when (and user-id (contains? valid-languages language))
    (jdbc/execute-one! (get-conn ds)
      ["UPDATE users SET language = ? WHERE id = ? RETURNING id, language" language user-id]
      {:builder-fn rs/as-unqualified-maps})))

(defn- query-categories-chunked [conn task-ids]
  (if (empty? task-ids)
    []
    (let [chunk-size 500
          chunks (partition-all chunk-size task-ids)]
      (mapcat (fn [chunk]
                (jdbc/execute! conn
                  (into [(str "SELECT task_id, category_type, category_id FROM task_categories WHERE task_id IN ("
                              (clojure.string/join "," (repeat (count chunk) "?"))
                              ")")] chunk)
                  {:builder-fn rs/as-unqualified-maps}))
              chunks))))

(defn- normalize-task [task]
  (-> task
      (update :description #(or % ""))
      (update :sort_order #(or % 0.0))
      (update :due_time #(when (and % (not= % "")) %))))

(defn export-all-data [ds user-id]
  (let [conn (get-conn ds)
        {:keys [clause params]} (user-id-clause user-id)
        tasks (jdbc/execute! conn
                (into [(str "SELECT " (clojure.string/join ", " (map name task-select-columns)) " FROM tasks WHERE " clause " ORDER BY created_at")] params)
                {:builder-fn rs/as-unqualified-maps})
        task-ids (mapv :id tasks)
        categories (query-categories-chunked conn task-ids)
        people (jdbc/execute! conn
                 (into [(str "SELECT id, name, description, sort_order FROM people WHERE " clause " ORDER BY sort_order ASC, name ASC")] params)
                 {:builder-fn rs/as-unqualified-maps})
        places (jdbc/execute! conn
                 (into [(str "SELECT id, name, description, sort_order FROM places WHERE " clause " ORDER BY sort_order ASC, name ASC")] params)
                 {:builder-fn rs/as-unqualified-maps})
        projects (jdbc/execute! conn
                   (into [(str "SELECT id, name, description, sort_order FROM projects WHERE " clause " ORDER BY sort_order ASC, name ASC")] params)
                   {:builder-fn rs/as-unqualified-maps})
        goals (jdbc/execute! conn
                (into [(str "SELECT id, name, description, sort_order FROM goals WHERE " clause " ORDER BY sort_order ASC, name ASC")] params)
                {:builder-fn rs/as-unqualified-maps})
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
  (jdbc/execute-one! (get-conn ds)
    ["INSERT INTO messages (sender, title, description, user_id) VALUES (?, ?, ?, ?) RETURNING id, sender, title, description, created_at, done, user_id"
     sender title (or description "") user-id]
    {:builder-fn rs/as-unqualified-maps}))

(defn list-messages
  ([ds user-id] (list-messages ds user-id :recent))
  ([ds user-id sort-mode]
   (let [{:keys [clause params]} (user-id-clause user-id)
         where-clause (case sort-mode
                        :done (str "WHERE " clause " AND done = 1")
                        (str "WHERE " clause " AND done = 0"))
         order-clause (case sort-mode
                        :done "ORDER BY created_at DESC"
                        "ORDER BY created_at DESC")]
     (jdbc/execute! (get-conn ds)
       (into [(str "SELECT id, sender, title, description, created_at, done FROM messages " where-clause " " order-clause)] params)
       {:builder-fn rs/as-unqualified-maps}))))

(defn message-owned-by-user? [ds message-id user-id]
  (let [{:keys [clause params]} (user-id-clause user-id)
        query-params (concat [message-id] params)]
    (some? (jdbc/execute-one! (get-conn ds)
             (into [(str "SELECT id FROM messages WHERE id = ? AND " clause)] query-params)
             {:builder-fn rs/as-unqualified-maps}))))

(defn set-message-done [ds user-id message-id done?]
  (let [{:keys [clause params]} (user-id-clause user-id)
        done-val (if done? 1 0)
        query-params (concat [done-val message-id] params)]
    (jdbc/execute-one! (get-conn ds)
      (into [(str "UPDATE messages SET done = ? WHERE id = ? AND " clause " RETURNING id, done")] query-params)
      {:builder-fn rs/as-unqualified-maps})))

(defn delete-message [ds user-id message-id]
  (when (message-owned-by-user? ds message-id user-id)
    (let [result (jdbc/execute-one! (get-conn ds)
                   ["DELETE FROM messages WHERE id = ?" message-id])]
      {:success (pos? (:next.jdbc/update-count result))})))
