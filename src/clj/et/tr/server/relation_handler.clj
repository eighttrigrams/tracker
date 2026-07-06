(ns et.tr.server.relation-handler
  (:require [et.tr.server.common :as common]
            [et.tr.server.events :as events]
            [et.tr.db :as db]
            [et.tr.db.relation :as db.relation]
            [et.tr.db.issue :as db.issue]))

(def ^:private valid-relation-types #{"tsk" "res" "met" "jen" "iss"})

(defn- task-issue-pair
  "When a relation is between a task and an issue, return {:task-id :issue-id}
  (order-independent); otherwise nil. Task↔issue membership is a belongs-to FK
  on the task, not a generic bidirectional relations row."
  [source-type source-id target-type target-id]
  (cond
    (and (= source-type "tsk") (= target-type "iss")) {:task-id source-id :issue-id target-id}
    (and (= source-type "iss") (= target-type "tsk")) {:task-id target-id :issue-id source-id}))

(defn add-relation-handler
  "POST /api/relations — create a relation between two items. Body fields:
  :source-type, :source-id, :target-type, :target-id. Each *-type must be in
  #{\"tsk\" \"res\" \"met\" \"jen\"}; each *-id must be an integer. Returns
  201 with the new relation, 400 {:success false :error} on validation
  failure, or 404 when an item is missing. Records a :relation-add event
  with both endpoint titles."
  [req]
  (let [user-id (common/get-user-id req)
        {:keys [source-type source-id target-type target-id]} (:body req)]
    (cond
      (not (contains? valid-relation-types source-type))
      {:status 400 :body {:success false :error "Invalid source-type"}}

      (not (contains? valid-relation-types target-type))
      {:status 400 :body {:success false :error "Invalid target-type"}}

      (or (nil? source-id) (not (integer? source-id)))
      {:status 400 :body {:success false :error "source-id must be an integer"}}

      (or (nil? target-id) (not (integer? target-id)))
      {:status 400 :body {:success false :error "target-id must be an integer"}}

      (and (= source-type target-type) (= source-id target-id))
      {:status 400 :body {:success false :error "An item cannot be related to itself"}}

      (task-issue-pair source-type source-id target-type target-id)
      (let [{:keys [task-id issue-id]} (task-issue-pair source-type source-id target-type target-id)]
        (if-let [result (db.issue/set-task-issue (common/ensure-ds) user-id task-id issue-id)]
          (let [conn (db/get-conn (common/ensure-ds))
                previous-issue-id (:previous-issue-id result)
                tsk-title (:title (db.relation/fetch-title-for-relation conn "tsk" task-id))
                iss-title (:title (db.relation/fetch-title-for-relation conn "iss" issue-id))]
            ;; Reassigning a task that already belonged to a different issue silently
            ;; drops the old membership; record its unlink so the audit log stays symmetric.
            (when (and previous-issue-id (not= previous-issue-id issue-id))
              (let [prev-title (:title (db.relation/fetch-title-for-relation conn "iss" previous-issue-id))]
                (events/record! req {:entity-type :relation
                                     :entity-id nil
                                     :action :relation-delete
                                     :payload {:source {:type "tsk" :id task-id :title tsk-title}
                                               :target {:type "iss" :id previous-issue-id :title prev-title}}})))
            (events/record! req {:entity-type :relation
                                 :entity-id nil
                                 :action :relation-add
                                 :payload {:source {:type "tsk" :id task-id :title tsk-title}
                                           :target {:type "iss" :id issue-id :title iss-title}}})
            {:status 201 :body {:success true}})
          {:status 404 :body {:success false :error "Item not found"}}))

      :else
      (if-let [result (db.relation/add-relation (common/ensure-ds) user-id source-type source-id target-type target-id)]
        (let [conn (db/get-conn (common/ensure-ds))
              src-title (:title (db.relation/fetch-title-for-relation conn source-type source-id))
              tgt-title (:title (db.relation/fetch-title-for-relation conn target-type target-id))]
          (events/record! req {:entity-type :relation
                               :entity-id nil
                               :action :relation-add
                               :payload {:source {:type source-type :id source-id :title src-title}
                                         :target {:type target-type :id target-id :title tgt-title}}})
          {:status 201 :body result})
        {:status 404 :body {:success false :error "Item not found"}}))))

(defn delete-relation-handler
  "DELETE /api/relations — remove an existing relation. Body fields are the
  same as add-relation-handler: :source-type, :source-id, :target-type,
  :target-id, with the same #{\"tsk\" \"res\" \"met\" \"jen\"} and integer
  validation. Returns 200 with the deletion result, 400 {:success false
  :error} on invalid input, or 404 when no matching relation exists.
  Records a :relation-delete event with both endpoint titles."
  [req]
  (let [user-id (common/get-user-id req)
        {:keys [source-type source-id target-type target-id]} (:body req)]
    (cond
      (not (contains? valid-relation-types source-type))
      {:status 400 :body {:success false :error "Invalid source-type"}}

      (not (contains? valid-relation-types target-type))
      {:status 400 :body {:success false :error "Invalid target-type"}}

      (or (nil? source-id) (not (integer? source-id)))
      {:status 400 :body {:success false :error "source-id must be an integer"}}

      (or (nil? target-id) (not (integer? target-id)))
      {:status 400 :body {:success false :error "target-id must be an integer"}}

      (task-issue-pair source-type source-id target-type target-id)
      (let [{:keys [task-id issue-id]} (task-issue-pair source-type source-id target-type target-id)]
        (if (db.issue/clear-task-issue (common/ensure-ds) user-id task-id issue-id)
          (let [conn (db/get-conn (common/ensure-ds))
                tsk-title (:title (db.relation/fetch-title-for-relation conn "tsk" task-id))
                iss-title (:title (db.relation/fetch-title-for-relation conn "iss" issue-id))]
            (events/record! req {:entity-type :relation
                                 :entity-id nil
                                 :action :relation-delete
                                 :payload {:source {:type "tsk" :id task-id :title tsk-title}
                                           :target {:type "iss" :id issue-id :title iss-title}}})
            {:status 200 :body {:success true}})
          {:status 404 :body {:success false :error "Item not found"}}))

      :else
      (if-let [result (db.relation/delete-relation (common/ensure-ds) user-id source-type source-id target-type target-id)]
        (let [conn (db/get-conn (common/ensure-ds))
              src-title (:title (db.relation/fetch-title-for-relation conn source-type source-id))
              tgt-title (:title (db.relation/fetch-title-for-relation conn target-type target-id))]
          (events/record! req {:entity-type :relation
                               :entity-id nil
                               :action :relation-delete
                               :payload {:source {:type source-type :id source-id :title src-title}
                                         :target {:type target-type :id target-id :title tgt-title}}})
          {:status 200 :body result})
        {:status 404 :body {:success false :error "Item not found"}}))))

(defn get-relations-handler
  "GET /api/relations/:type/:id — list all relations attached to one item.
  Path params: :type (one of \"tsk\"/\"res\"/\"met\"/\"jen\") and :id
  (parsed as an integer). Returns 200 with a vector of relations enriched
  with endpoint titles (empty when none), or 400 {:error} when :type is not
  recognised."
  [req]
  (let [user-id (common/get-user-id req)
        item-type (get-in req [:params :type])
        item-id (Integer/parseInt (get-in req [:params :id]))]
    (if (contains? valid-relation-types item-type)
      {:status 200 :body (or (db.relation/get-relations-with-titles (common/ensure-ds) user-id item-type item-id) [])}
      {:status 400 :body {:error "Invalid item type"}})))
