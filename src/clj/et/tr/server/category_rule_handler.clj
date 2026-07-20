(ns et.tr.server.category-rule-handler
  (:require [et.tr.server.common :as common]
            [et.tr.db :as db]
            [et.tr.db.category-rule :as db.category-rule]))

(defn list-rules-handler
  "GET /api/category-rules — list the caller's rules, each enriched with the
  source/target category names for display."
  [req]
  (let [user-id (common/get-user-id req)]
    {:status 200 :body (db.category-rule/list-rules (common/ensure-ds) user-id)}))

(defn- valid-type? [t]
  (contains? db/valid-category-types t))

(defn add-rule-handler
  "POST /api/category-rules — create a rule source -> target. Body:
  {:source-type :source-id :target-type :target-id}. Rejects blank/invalid
  types and non-positive ids with 400. Self-pairs and unowned categories yield
  400. On success returns 201 with the inserted rule row."
  [req]
  (let [user-id (common/get-user-id req)
        {:keys [source-type source-id target-type target-id]} (:body req)]
    (cond
      (or (not (valid-type? source-type)) (not (valid-type? target-type)))
      {:status 400 :body {:success false :error "Invalid category type"}}

      (or (not (integer? source-id)) (< source-id 1)
          (not (integer? target-id)) (< target-id 1))
      {:status 400 :body {:success false :error "category id must be a positive integer"}}

      (and (= source-type target-type) (= source-id target-id))
      {:status 400 :body {:success false :error "A rule cannot point to itself"}}

      :else
      (if-let [row (db.category-rule/add-rule (common/ensure-ds) user-id source-type source-id target-type target-id)]
        {:status 201 :body row}
        {:status 400 :body {:success false :error "Could not create rule"}}))))

(defn delete-rule-handler
  "DELETE /api/category-rules/:id — delete one of the caller's rules. Returns
  200 {:success true} on success, 404 when the rule does not exist or is not
  owned."
  [req]
  (let [user-id (common/get-user-id req)
        rule-id (Integer/parseInt (get-in req [:params :id]))
        result (db.category-rule/delete-rule (common/ensure-ds) user-id rule-id)]
    (if (:success result)
      {:status 200 :body {:success true}}
      {:status 404 :body {:success false :error "Rule not found"}})))

(defn resolve-handler
  "POST /api/category-rules/resolve — given a seed category
  {:category-type :category-id}, return the transitive closure of rule targets
  as {:categories [{:category-type :category-id}...]} (seed included). Used by
  the client to auto-select rule targets when a sidebar filter is toggled on."
  [req]
  (let [user-id (common/get-user-id req)
        {:keys [category-type category-id]} (:body req)]
    (cond
      (not (valid-type? category-type))
      {:status 400 :body {:success false :error "Invalid category type"}}

      (or (not (integer? category-id)) (< category-id 1))
      {:status 400 :body {:success false :error "category-id must be a positive integer"}}

      :else
      (let [closure (db.category-rule/resolve-closure (common/ensure-ds) user-id [[category-type category-id]])]
        {:status 200 :body {:categories (mapv (fn [[ct cid]] {:category-type ct :category-id cid}) closure)}}))))
