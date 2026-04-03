(ns et.tr.db.message
  (:require [next.jdbc :as jdbc]
            [clojure.string :as str]
            [honey.sql :as sql]
            [taoensso.telemere :as tel]
            [et.tr.db :as db]))

(defn add-message [ds user-id sender title description type scope importance urgency]
  (let [result (jdbc/execute-one! (db/get-conn ds)
                 (sql/format {:insert-into :messages
                              :values [{:sender sender
                                        :title title
                                        :description (or description "")
                                        :type (when-not (str/blank? type) type)
                                        :scope (when (contains? #{"private" "work"} scope) scope)
                                        :importance (if (contains? db/valid-importances importance) importance "normal")
                                        :urgency (if (contains? db/valid-urgencies urgency) urgency "default")
                                        :user_id user-id}]
                              :returning [:id :sender :title :description :created_at :done :type :scope :importance :urgency :user_id]})
                 db/jdbc-opts)]
    (tel/log! {:level :info :data {:message-id (:id result) :user-id user-id}} "Message added")
    result))

(defn- build-message-scope-clause [context strict]
  (when context
    (if strict
      (if (= context "both")
        [:is :scope nil]
        [:= :scope context])
      (case context
        "private" [:or [:= :scope "private"] [:is :scope nil]]
        "work" [:or [:= :scope "work"] [:is :scope nil]]
        nil))))

(defn list-messages
  ([ds user-id] (list-messages ds user-id {}))
  ([ds user-id opts]
   (let [{:keys [sort-mode sender-filter excluded-senders context strict importance urgency]
          :or {sort-mode :recent}} opts
         user-where (db/user-id-where-clause user-id)
         done-filter (case sort-mode
                       :done [:= :done 1]
                       [:= :done 0])
         order-dir (if (= sort-mode :reverse) :asc :desc)
         scope-clause (build-message-scope-clause context strict)
         importance-clause (db/build-importance-clause importance)
         urgency-clause (db/build-urgency-clause urgency)
         where-clause (cond-> [:and user-where done-filter]
                        sender-filter (conj [:= :sender sender-filter])
                        (seq excluded-senders) (conj [:not-in :sender excluded-senders])
                        scope-clause (conj scope-clause)
                        importance-clause (conj importance-clause)
                        urgency-clause (conj urgency-clause))]
     (jdbc/execute! (db/get-conn ds)
       (sql/format {:select [:id :sender :title :description :created_at :done :annotation :type :scope :importance :urgency]
                    :from [:messages]
                    :where where-clause
                    :order-by [[:created_at order-dir]]})
       db/jdbc-opts))))

(defn message-owned-by-user? [ds message-id user-id]
  (some? (jdbc/execute-one! (db/get-conn ds)
           (sql/format {:select [:id]
                        :from [:messages]
                        :where [:and [:= :id message-id] (db/user-id-where-clause user-id)]})
           db/jdbc-opts)))

(defn get-message [ds user-id message-id]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:select [:id :sender :title :description :annotation]
                 :from [:messages]
                 :where [:and [:= :id message-id] (db/user-id-where-clause user-id)]})
    db/jdbc-opts))

(defn set-message-done [ds user-id message-id done?]
  (let [done-val (if done? 1 0)]
    (jdbc/execute-one! (db/get-conn ds)
      (sql/format {:update :messages
                   :set {:done done-val}
                   :where [:and [:= :id message-id] (db/user-id-where-clause user-id)]
                   :returning [:id :done]})
      db/jdbc-opts)))

(defn delete-message [ds user-id message-id]
  (when (message-owned-by-user? ds message-id user-id)
    (let [result (jdbc/execute-one! (db/get-conn ds)
                   (sql/format {:delete-from :messages
                                :where [:= :id message-id]}))]
      (tel/log! {:level :info :data {:message-id message-id :user-id user-id}} "Message deleted")
      {:success (pos? (:next.jdbc/update-count result))})))

(defn update-message-annotation [ds user-id message-id annotation]
  (when (message-owned-by-user? ds message-id user-id)
    (jdbc/execute-one! (db/get-conn ds)
      (sql/format {:update :messages
                   :set {:annotation (or annotation "")}
                   :where [:and [:= :id message-id] (db/user-id-where-clause user-id)]
                   :returning [:id :annotation]})
      db/jdbc-opts)))

(defn set-message-scope [ds user-id message-id scope]
  (let [scope-val (when (contains? #{"private" "work"} scope) scope)]
    (jdbc/execute-one! (db/get-conn ds)
      (sql/format {:update :messages
                   :set {:scope scope-val}
                   :where [:and [:= :id message-id] (db/user-id-where-clause user-id)]
                   :returning [:id :scope]})
      db/jdbc-opts)))

(defn set-message-importance [ds user-id message-id importance]
  (let [importance-val (if (contains? db/valid-importances importance) importance "normal")]
    (jdbc/execute-one! (db/get-conn ds)
      (sql/format {:update :messages
                   :set {:importance importance-val}
                   :where [:and [:= :id message-id] (db/user-id-where-clause user-id)]
                   :returning [:id :importance]})
      db/jdbc-opts)))

(defn set-message-urgency [ds user-id message-id urgency]
  (let [urgency-val (if (contains? db/valid-urgencies urgency) urgency "default")]
    (jdbc/execute-one! (db/get-conn ds)
      (sql/format {:update :messages
                   :set {:urgency urgency-val}
                   :where [:and [:= :id message-id] (db/user-id-where-clause user-id)]
                   :returning [:id :urgency]})
      db/jdbc-opts)))

(defn merge-messages [ds user-id source-id target-id]
  (when (and (message-owned-by-user? ds source-id user-id)
             (message-owned-by-user? ds target-id user-id))
    (let [conn (db/get-conn ds)
          source (get-message ds user-id source-id)
          target (get-message ds user-id target-id)]
      (when (and source target)
        (let [merged-title (str (:title target) " :: " (:title source))]
          (jdbc/execute-one! conn
            (sql/format {:update :messages
                         :set {:title merged-title}
                         :where [:= :id target-id]})
            db/jdbc-opts)
          (jdbc/execute-one! conn
            (sql/format {:delete-from :messages
                         :where [:= :id source-id]}))
          (tel/log! {:level :info :data {:source-id source-id :target-id target-id :user-id user-id}} "Messages merged")
          {:success true})))))
