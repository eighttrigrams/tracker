(ns et.tr.server.events
  "Bridge between HTTP handlers and the events table. Handlers call
  `record!` to log a change after the DB write succeeds. The actor is
  derived from the request once."
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [et.tr.db :as db]
            [et.tr.server.common :as common]
            [et.tr.db.event :as db.event]))

(def ^:private category-table
  {"person" :people "place" :places "project" :projects "goal" :goals})

(defn fetch-category-title [category-type category-id]
  (when-let [tbl (category-table category-type)]
    (:name (jdbc/execute-one! (db/get-conn (common/ensure-ds))
             (sql/format {:select [:name] :from [tbl] :where [:= :id category-id]})
             db/jdbc-opts))))

(defn fetch-row [table id]
  (when (and table id)
    (jdbc/execute-one! (db/get-conn (common/ensure-ds))
      (sql/format {:select [:*] :from [(keyword table)] :where [:= :id id]})
      db/jdbc-opts)))

(defn fetch-fields [table id fields]
  (when (and table id (seq fields))
    (jdbc/execute-one! (db/get-conn (common/ensure-ds))
      (sql/format {:select fields :from [(keyword table)] :where [:= :id id]})
      db/jdbc-opts)))

(defn- presentable [row]
  (when row
    (-> row
        (dissoc :modified_at :user_id :created_at :password_hash)
        (cond-> (contains? row :is_machine_user)
          (update :is_machine_user #(= 1 %)))
        (cond-> (contains? row :mail_only)
          (update :mail_only #(= 1 %))))))

(defn record!
  "Record an event for the change described by `event-map`. `req` is the
  Ring request (used to resolve the actor). Returns nil. Never throws —
  any exception (including from the actor lookup or from db.event) is
  swallowed so a user-visible write is never affected by audit failures."
  [req {:keys [entity-type entity-id action payload dropped system?]}]
  (try
    (when-let [actor (common/get-actor req)]
      (db.event/record-event! (common/ensure-ds) actor
                              {:entity-type entity-type
                               :entity-id entity-id
                               :action (or action :update)
                               :payload payload
                               :dropped dropped
                               :system? system?}))
    (catch Throwable _ nil)))

(defn record-create! [req entity-type entity-id row]
  (record! req {:entity-type entity-type
                :entity-id entity-id
                :action :create
                :payload {:row (presentable row)}}))

(defn record-create-with-actor!
  "Like record-create!, but takes a pre-resolved actor map and an explicit
  effective-user-id. Used by background workers that have no Ring request."
  [ds actor effective-user-id entity-type entity-id row]
  (try
    (when actor
      (db.event/record-event! ds actor
                              {:entity-type entity-type
                               :entity-id entity-id
                               :action :create
                               :payload {:row (presentable row)}
                               :effective-user-id effective-user-id}))
    (catch Throwable _ nil)))

(defn record-update!
  "Diff before vs after, emit an :update event only if at least one tracked
  field changed. When exactly one field changed, payload is flattened to
  {:field … :old-value … :new-value …}; otherwise {:changes {field {:old …
  :new …}}}."
  [req entity-type entity-id before after]
  (let [diff (db.event/diff-fields (presentable before) (presentable after))]
    (when (seq diff)
      (let [payload (if (= 1 (count diff))
                      (let [[k {:keys [old new]}] (first diff)]
                        {:field (name k) :old-value old :new-value new})
                      {:changes (reduce-kv (fn [m k v] (assoc m (name k) v)) {} diff)})]
        (record! req {:entity-type entity-type
                      :entity-id entity-id
                      :action :update
                      :payload payload})))))

(defn record-delete! [req entity-type entity-id snapshot]
  (record! req {:entity-type entity-type
                :entity-id entity-id
                :action :delete
                :payload {:snapshot (presentable snapshot)}}))

(defn record-link! [req entity-type entity-id category-type category-id category-title]
  (record! req {:entity-type entity-type
                :entity-id entity-id
                :action :link
                :payload {:category-type category-type
                          :category-id category-id
                          :category-title category-title}}))

(defn record-unlink! [req entity-type entity-id category-type category-id category-title]
  (record! req {:entity-type entity-type
                :entity-id entity-id
                :action :unlink
                :payload {:category-type category-type
                          :category-id category-id
                          :category-title category-title}}))
