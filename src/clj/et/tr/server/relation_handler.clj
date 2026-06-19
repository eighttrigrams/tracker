(ns et.tr.server.relation-handler
  (:require [et.tr.server.common :as common]
            [et.tr.server.events :as events]
            [et.tr.db :as db]
            [et.tr.db.relation :as db.relation]))

(def ^:private valid-relation-types #{"tsk" "res" "met" "jen"})

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
