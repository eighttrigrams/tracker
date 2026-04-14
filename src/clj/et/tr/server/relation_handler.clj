(ns et.tr.server.relation-handler
  (:require [et.tr.server.common :as common]
            [et.tr.db.relation :as db.relation]))

(def ^:private valid-relation-types #{"tsk" "res" "met" "jen"})

(defn add-relation-handler [req]
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
      (if-let [result (db.relation/add-relation (common/ensure-ds) user-id source-type source-id target-type target-id)]
        {:status 201 :body result}
        {:status 404 :body {:success false :error "Item not found"}}))))

(defn delete-relation-handler [req]
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
        {:status 200 :body result}
        {:status 404 :body {:success false :error "Item not found"}}))))

(defn get-relations-handler [req]
  (let [user-id (common/get-user-id req)
        item-type (get-in req [:params :type])
        item-id (Integer/parseInt (get-in req [:params :id]))]
    (if (contains? valid-relation-types item-type)
      {:status 200 :body (or (db.relation/get-relations-with-titles (common/ensure-ds) user-id item-type item-id) [])}
      {:status 400 :body {:error "Invalid item type"}})))
