(ns et.tr.db.message
  (:require [next.jdbc :as jdbc]
            [clojure.string :as str]
            [honey.sql :as sql]
            [taoensso.telemere :as tel]
            [et.tr.db :as db]
            [et.tr.db.category-rule :as db.category-rule]
            [et.tr.db.category-exclusion :as db.category-exclusion]))

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
                                        :modified_at [:raw "datetime('now')"]
                                        :user_id user-id}]
                              :returning [:id :sender :title :description :created_at :modified_at :done :type :scope :importance :urgency :user_id]})
                 db/jdbc-opts)]
    (tel/log! {:level :info :data {:message-id (:id result) :user-id user-id}} "Message added")
    ;; A fresh message carries every Group key, empty — the same shape a listed
    ;; one has. Without it the client's copy of a just-added message is the one
    ;; row in the list missing :people/:places/…, and the card that renders
    ;; categories off those keys reads the absence as "none" only by luck.
    (merge result db/empty-category-groups)))

(defn- build-message-category-clauses [categories]
  (db/build-category-clauses :message_categories :message_id :messages categories))

(defn- associate-categories-with-messages [messages categories-by-message lookups]
  (db/assoc-category-groups messages categories-by-message lookups))

(defn- attach-categories
  "Decorate rows with their Group keys, the three-step every other list does:
  read the join rows for the ids in hand, read the Group lookups once, fold.

  Split out rather than written twice because `list-messages` and `get-message`
  want the same thing for a vector and for one row, and the one-row case going
  its own way is how a field ends up present in a listing and missing from the
  detail."
  [conn user-where rows opts]
  (let [ids (mapv :id rows)
        joins (when (seq ids)
                (jdbc/execute! conn
                  (sql/format {:select [:message_id :category_type :category_id]
                               :from [:message_categories]
                               :where [:in :message_id ids]})
                  db/jdbc-opts))
        lookups (db/fetch-category-lookups conn user-where opts)]
    (associate-categories-with-messages rows (group-by :message_id joins) lookups)))

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
   (let [{:keys [view sort-mode sender-filter excluded-senders context strict importance urgency search-term limit
                 categories excluded-categories]
          :or {view :inbox sort-mode :recent}} opts
         conn (db/get-conn ds)
         user-where (db/user-id-where-clause user-id)
         done-filter (case view
                       :saved [:= :done 1]
                       [:= :done 0])
         order-dir (if (= sort-mode :reverse) :asc :desc)
         scope-clause (build-message-scope-clause context strict)
         importance-clause (db/build-importance-clause importance)
         urgency-clause (db/build-urgency-clause urgency)
         search-clause (db/build-search-clause search-term [:title :description])
         category-clauses (build-message-category-clauses categories)
         exclusion-clauses (db.category-exclusion/build-exclusion-clauses
                            ds user-id :message_categories :message_id :messages excluded-categories)
         where-clause (into (cond-> [:and user-where done-filter]
                              sender-filter (conj [:= :sender sender-filter])
                              (seq excluded-senders) (conj [:not-in :sender excluded-senders])
                              scope-clause (conj scope-clause)
                              importance-clause (conj importance-clause)
                              urgency-clause (conj urgency-clause)
                              search-clause (conj search-clause))
                            (concat category-clauses exclusion-clauses))
         messages (jdbc/execute! conn
                    (sql/format (cond-> {:select [:id :sender :title :description :created_at :modified_at :done :type :scope :importance :urgency]
                                         :from [:messages]
                                         :where where-clause
                                         :order-by [[:created_at order-dir]]}
                                  limit (assoc :limit limit)))
                    db/jdbc-opts)]
     (attach-categories conn user-where messages {:context context :strict strict}))))

(defn message-owned-by-user? [ds message-id user-id]
  (some? (jdbc/execute-one! (db/get-conn ds)
           (sql/format {:select [:id]
                        :from [:messages]
                        :where [:and [:= :id message-id] (db/user-id-where-clause user-id)]})
           db/jdbc-opts)))

(defn get-message [ds user-id message-id]
  (let [conn (db/get-conn ds)
        user-where (db/user-id-where-clause user-id)
        message (jdbc/execute-one! conn
                  (sql/format {:select [:id :sender :title :description :created_at :modified_at :done :type :scope :importance :urgency]
                               :from [:messages]
                               :where [:and [:= :id message-id] user-where]})
                  db/jdbc-opts)]
    (when message
      (first (attach-categories conn user-where [message] nil)))))

(defn set-message-done [ds user-id message-id done?]
  (let [done-val (if done? 1 0)]
    (jdbc/execute-one! (db/get-conn ds)
      (sql/format {:update :messages
                   :set {:done done-val}
                   :where [:and [:= :id message-id] (db/user-id-where-clause user-id)]
                   :returning [:id :done]})
      db/jdbc-opts)))

(defn drop-category-links!
  "Forget a message's Category links. Call it in the same transaction that
  removes the message.

  **The declared `ON DELETE CASCADE` does not do this.** SQLite enforces foreign
  keys only under `PRAGMA foreign_keys = ON`, which this app never sets, so every
  cascade in the schema is decoration — `issue_categories` and the rest have the
  same latent condition and leave rows behind today. That is worth reporting
  separately; it is not worth discovering here, on the one entity that is deleted
  rather than archived. The Inbox is a queue whose whole purpose is to empty, and
  every convert deletes a row, so this is the one join table where orphans would
  arrive continuously rather than occasionally."
  [tx message-id]
  (jdbc/execute-one! tx
    (sql/format {:delete-from :message_categories
                 :where [:= :message_id message-id]})))

(defn move-category-links!
  "Hand a message's Category links to the row that replaces it, then forget them.

  Both conversions end by deleting the message, and a filing the owner did in the
  Inbox is a filing they meant — losing it at the moment of triage would make
  categorizing a message useful only until you acted on it. `:on-conflict
  :do-nothing` because the destination may already carry the Category: a rule
  closure could have put it there, and a link is a set member, not a count."
  [tx join-table entity-col entity-id message-id]
  (doseq [{:keys [category_type category_id]}
          (jdbc/execute! tx
            (sql/format {:select [:category_type :category_id]
                         :from [:message_categories]
                         :where [:= :message_id message-id]})
            db/jdbc-opts)]
    (jdbc/execute-one! tx
      (sql/format {:insert-into join-table
                   :values [{entity-col entity-id
                             :category_type category_type
                             :category_id category_id}]
                   :on-conflict []
                   :do-nothing true})))
  (drop-category-links! tx message-id))

(defn delete-message [ds user-id message-id]
  (when (message-owned-by-user? ds message-id user-id)
    (let [conn (db/get-conn ds)
          result (jdbc/with-transaction [tx conn]
                   (drop-category-links! tx message-id)
                   (jdbc/execute-one! tx
                     (sql/format {:delete-from :messages
                                  :where [:and [:= :id message-id] (db/user-id-where-clause user-id)]})))]
      (tel/log! {:level :info :data {:message-id message-id :user-id user-id}} "Message deleted")
      {:success (pos? (:next.jdbc/update-count result))})))

(defn update-message
  ([ds user-id message-id title description] (update-message ds user-id message-id title description nil))
  ([ds user-id message-id title description expected-modified-at]
   (when (message-owned-by-user? ds message-id user-id)
     (jdbc/execute-one! (db/get-conn ds)
       (sql/format {:update :messages
                    :set {:title title
                          :description (or description "")
                          :modified_at [:raw "datetime('now')"]}
                    :where (db/update-where message-id user-id expected-modified-at)
                    :returning [:id :title :description :modified_at]})
       db/jdbc-opts))))

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


(defn categorize-message
  "Link a message to one Category, and to whatever that Category's rules pull in
  with it.

  The same shape as `categorize-resource`, including the rule closure: a rule
  that says a Project implies its Workstream has to hold on the Inbox too, or the
  filter that finds the Workstream would not find the message filed under the
  Project."
  [ds user-id message-id category-type category-id]
  (db/validate-category-type! category-type)
  (when (and (message-owned-by-user? ds message-id user-id)
             (db/category-owned-by-user? ds category-type category-id user-id))
    (let [conn (db/get-conn ds)
          closure (db.category-rule/resolve-closure ds user-id [[category-type category-id]])]
      (jdbc/with-transaction [tx conn]
        (let [applied (db.category-rule/apply-closure! tx :message_categories :message_id message-id closure)]
          (jdbc/execute-one! tx
            (sql/format {:update :messages
                         :set {:modified_at [:raw "datetime('now')"]}
                         :where [:= :id message-id]}))
          applied)))))

(defn uncategorize-message
  "Unlink one Category from a message. Only the one named: a closure applied on
  the way in is not unwound on the way out, which is what every other kind does
  — the rule said what to add, not what the owner may take off."
  [ds user-id message-id category-type category-id]
  (db/validate-category-type! category-type)
  (when (and (message-owned-by-user? ds message-id user-id)
             (db/category-owned-by-user? ds category-type category-id user-id))
    (let [conn (db/get-conn ds)]
      (jdbc/with-transaction [tx conn]
        (jdbc/execute-one! tx
          (sql/format {:delete-from :message_categories
                       :where [:and
                               [:= :message_id message-id]
                               [:= :category_type category-type]
                               [:= :category_id category-id]]}))
        (jdbc/execute-one! tx
          (sql/format {:update :messages
                       :set {:modified_at [:raw "datetime('now')"]}
                       :where [:= :id message-id]}))))))
