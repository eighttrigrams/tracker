(ns datastore.contexts
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [datastore.helpers
             :refer [un-namespace-keys simplify-date]]))

(defn new-context [db {title :title}]
  (-> (jdbc/execute-one!
       db
       (sql/format {:insert-into [:contexts]
                    :columns     [:inserted_at
                                  :updated_at
                                  :title
                                  :search_mode]
                    :values      [[[:raw "NOW()"]
                                   [:raw "NOW()"]
                                   [:inline title]
                                   [:inline 0]]]})
       {:return-keys true})
      un-namespace-keys
      (dissoc :searchable)))

(defn- update-context* [db {:keys [id title short_title tags]}]
  (jdbc/execute-one! db
                     (sql/format {:update [:contexts]
                                  :where  [:= :id [:inline id]]
                                  :set    {:title       [:inline title]
                                           :short_title [:inline short_title]
                                           :tags        [:inline tags]
                                           :updated_at  [:raw "NOW()"]}})
                     {:return-keys true}))

(defn- join-secondary-contexts [context]
  (-> context
      (dissoc :secondary_contexts_ids)
      (dissoc :secondary_contexts_titles)
      (assoc :secondary_contexts
             (zipmap (.getArray (:secondary_contexts_ids context))
                     (.getArray (:secondary_contexts_titles context))))))

(defn- simple-contexts-query [id]
  {:select   [:contexts.*]
   :from     [:contexts]
   :where    [:= :contexts.id [:inline id]]})

(defn- contexts-query [id]
  {:select   [:contexts.*
              [[:array_agg :secondary_contexts.id] :secondary_contexts_ids]
              [[:array_agg :secondary_contexts.title] :secondary_contexts_titles]]
   :from     [:contexts]
   :join     [:context_context [:= :contexts.id :context_context.parent_id]
              [:contexts :secondary_contexts] [:= :secondary_contexts.id :context_context.child_id]]
   :where    [:= :contexts.id [:inline id]]
   :group-by [:contexts.id]})

(defn- get-context-with-secondary-contexts [db id]
  (when-let [result (-> id
                        contexts-query
                        sql/format
                        (#(jdbc/execute-one! db % {:return-keys true})))]
    (join-secondary-contexts result)))

(defn get-context [db {:keys [id]}]
  (-> (if-let [context (get-context-with-secondary-contexts db id)]
        context
        (-> id
            simple-contexts-query
            sql/format
            (#(jdbc/execute-one! db % {:return-keys true}))))
      un-namespace-keys
      (dissoc :searchable)))

(defn- relate-contexts [db id secondary-contexts-ids]
  (doall
   (for [secondary-context-id secondary-contexts-ids]
     (jdbc/execute! db (sql/format {:insert-into [:context_context]
                                    :columns [:parent_id :child_id]
                                    :values [[[:inline id] [:inline secondary-context-id]]]})))))

(defn- delete-secondary-contexts [db id]
  (jdbc/execute! db (sql/format {:delete-from [:context_context]
                                 :where [:= :parent_id [:inline id]]})))

(defn update-context [db {:keys [context secondary-contexts-ids]}]
  (let [{:keys [id]} context]
    (delete-secondary-contexts db id)
    (relate-contexts db id secondary-contexts-ids)
    (update-context* db context)
    (get-context db context)))

(defn update-context-description [db {:keys [id description]}]
  (->
   (jdbc/execute-one! db
                      (sql/format {:update [:contexts]
                                   :set    {:description [:inline description]
                                            :updated_at  [:raw "NOW()"]}
                                   :where  [:= :id [:inline id]]})
                      {:return-keys true})
   un-namespace-keys
   (dissoc :searchable)))

(defn cycle-search-mode [db {:keys [id] :as context}]
  (let [context (get-context db context)]
    (jdbc/execute-one! db (sql/format {:update [:contexts]
                                       :set    {:search_mode [:inline (mod (inc (:search_mode context)) 3)]}
                                       :where  [:= :id [:inline id]]}))
    (get-context db context)))
