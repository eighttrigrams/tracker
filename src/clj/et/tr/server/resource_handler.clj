(ns et.tr.server.resource-handler
  (:require [et.tr.server.common :as common]
            [et.tr.server.events :as events]
            [et.tr.db :as db]
            [et.tr.db.resource :as db.resource]
            [clojure.string :as str]))

(defn get-resource-handler
  "GET /api/resources/:id — fetch a single resource owned by the caller.
  The :id path param is parsed as an integer. Returns 200 with the resource
  row or 404 {:error} if no row matches both the id and the caller's user-id."
  [req]
  (let [user-id (common/get-user-id req)
        resource-id (Integer/parseInt (get-in req [:params :id]))]
    (if-let [resource (db.resource/get-resource (common/ensure-ds) user-id resource-id)]
      {:status 200 :body resource}
      {:status 404 :body {:error "Resource not found"}})))

(defn list-resources-handler
  "GET /api/resources — list the caller's resources, filtered and sorted by
  query params. Recognised params: q (search term), importance, context,
  strict (\"true\" toggles strict context match), people/places/projects/goals
  (comma-separated category id lists), domain, excludedDomains
  (comma-separated), sortMode, limit (int — caps the row count; machine users
  default to 10 when omitted). Always returns 200 with a vector of rows."
  [req]
  (let [user-id (common/get-user-id req)
        search-term (get-in req [:params "q"])
        importance (get-in req [:params "importance"])
        context (get-in req [:params "context"])
        strict (= "true" (get-in req [:params "strict"]))
        people (common/parse-category-param (get-in req [:params "people"]))
        places (common/parse-category-param (get-in req [:params "places"]))
        projects (common/parse-category-param (get-in req [:params "projects"]))
        goals (common/parse-category-param (get-in req [:params "goals"]))
        domain (get-in req [:params "domain"])
        excluded-domains-param (get-in req [:params "excludedDomains"])
        excluded-domains (when (and excluded-domains-param (not (str/blank? excluded-domains-param)))
                           (set (str/split excluded-domains-param #",")))
        sort-mode (get-in req [:params "sortMode"])
        limit (common/parse-int-opt (get-in req [:params "limit"]))
        categories (when (or people places projects goals)
                     {:people people :places places :projects projects :goals goals})]
    {:status 200 :body (db.resource/list-resources (common/ensure-ds) user-id {:search-term search-term :importance importance :context context :strict strict :categories categories :domain domain :excluded-domains excluded-domains :sort-mode sort-mode :limit limit})}))

(defn add-resource-handler
  "POST /api/resources — create a new resource for the caller. Body fields:
  :title (required, non-blank), :link (optional, must start with http:// or
  https:// when present), :scope (defaults to \"both\"). YouTube links have
  their title auto-fetched and substituted. Returns 201 with the created row,
  or 400 {:success false :error} on validation failure. Records a :create
  event after a successful insert."
  [req]
  (let [user-id (common/get-user-id req)
        {:keys [title link scope]} (:body req)]
    (cond
      (str/blank? title)
      {:status 400 :body {:success false :error "Title is required"}}

      (and (seq link) (not (common/valid-url? link)))
      {:status 400 :body {:success false :error "Invalid URL. Must start with http:// or https://"}}

      :else
      (let [effective-link (when (seq link) link)
            title (if (and effective-link (common/youtube-url? effective-link))
                    (or (common/fetch-youtube-title effective-link) title)
                    title)
            resource (db.resource/add-resource (common/ensure-ds) user-id title effective-link (or scope "both"))]
        (events/record-create! req :resource (:id resource) resource)
        {:status 201 :body resource}))))

(defn update-resource-handler
  "PUT /api/resources/:id — update mutable fields on the caller's resource.
  Body fields: :title (required, non-blank), :link (optional, must be a valid
  http(s) URL when present), :description, :tags. Returns 200 with the
  updated row, or 400 {:success false :error} on validation failure. Records
  an :update event with the before/after diff."
  [req]
  (let [user-id (common/get-user-id req)
        resource-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [title link description tags]} (:body req)]
    (cond
      (str/blank? title)
      {:status 400 :body {:success false :error "Title is required"}}

      (and (seq link) (not (common/valid-url? link)))
      {:status 400 :body {:success false :error "Invalid URL. Must start with http:// or https://"}}

      :else
      (let [effective-link (when (seq link) link)
            before (events/fetch-fields :resources resource-id [:title :link :description :tags])
            resource (db.resource/update-resource (common/ensure-ds) user-id resource-id {:title title :link effective-link :description (or description "") :tags (or tags "")})]
        (events/record-update! req :resource resource-id before
                               (select-keys resource [:title :link :description :tags]))
        {:status 200 :body resource}))))

(defn set-resource-relation-badge-title-handler
  "PUT /api/resources/:id/relation-badge-title — set or clear the override
  badge title shown when this resource appears as a relation badge on another
  item. Body: {:relation-badge-title} (string; empty string clears). Returns
  the updated resource on 200, 404 if not found. Logs an :update event for
  :relation_badge_title."
  [req]
  (let [user-id (common/get-user-id req)
        resource-id (Integer/parseInt (get-in req [:params :id]))
        value (or (get-in req [:body :relation-badge-title]) "")
        before (events/fetch-fields :resources resource-id [:relation_badge_title])
        result (db.resource/set-resource-field (common/ensure-ds) user-id resource-id :relation_badge_title value)]
    (if result
      (do (events/record-update! req :resource resource-id before
                                 (select-keys result [:relation_badge_title]))
          {:status 200 :body result})
      {:status 404 :body {:error "Resource not found"}})))

(defn delete-resource-handler
  "DELETE /api/resources/:id — delete the caller's resource. Snapshots the
  row first so the audit log retains its contents. Returns 200 {:success
  true} on success, or 404 {:success false :error} when the row does not
  exist or is not owned by the caller. Records a :delete event with the
  snapshot."
  [req]
  (let [user-id (common/get-user-id req)
        resource-id (Integer/parseInt (get-in req [:params :id]))
        snapshot (events/fetch-row :resources resource-id)
        result (db.resource/delete-resource (common/ensure-ds) user-id resource-id)]
    (if (:success result)
      (do (events/record-delete! req :resource resource-id snapshot)
          {:status 200 :body {:success true}})
      {:status 404 :body {:success false :error "Resource not found"}})))

(def categorize-resource-handler
  "POST /api/resources/:id/categorize — link the resource to a category.
  Body fields: :category-type (\"person\"/\"place\"/\"project\"/\"goal\") and
  :category-id (positive integer). Returns 200 {:success true} on success,
  or 400 {:success false :error} when either field is missing or invalid.
  Records a :link event."
  (common/make-categorize-handler db.resource/categorize-resource :resource))

(def uncategorize-resource-handler
  "DELETE /api/resources/:id/categorize — unlink the resource from a
  category. Body fields: :category-type and :category-id, validated as for
  categorize. Returns 200 {:success true} on success, or 400 {:success false
  :error} on bad input. Records an :unlink event."
  (common/make-uncategorize-handler db.resource/uncategorize-resource :resource))

(defn reorder-resource-handler
  "POST /api/resources/:id/reorder — move the resource within the caller's
  manual ordering. Body fields: :target-resource-id (the neighbour to anchor
  on) and :position (\"before\" or \"after\"). Computes a new fractional
  sort_order halfway between the target and its neighbour (or one step past
  the edge when there is none). Returns 200 {:success true :sort_order}."
  [req]
  (let [user-id (common/get-user-id req)
        resource-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [target-resource-id position]} (:body req)
        all-resources (db.resource/list-resources (common/ensure-ds) user-id {:sort-mode "manual"})
        target-idx (->> all-resources
                        (map-indexed vector)
                        (some (fn [[idx r]] (when (= (:id r) target-resource-id) idx))))
        target-order (:sort_order (nth all-resources target-idx))
        neighbor-idx (if (= position "before") (dec target-idx) (inc target-idx))
        neighbor-order (when (and (>= neighbor-idx 0) (< neighbor-idx (count all-resources)))
                         (:sort_order (nth all-resources neighbor-idx)))
        new-order (cond
                    (nil? neighbor-order)
                    (if (= position "before")
                      (- target-order 1.0)
                      (+ target-order 1.0))
                    :else
                    (/ (+ target-order neighbor-order) 2.0))]
    (db.resource/reorder-resource (common/ensure-ds) user-id resource-id new-order)
    {:status 200 :body {:success true :sort_order new-order}}))

(def set-resource-scope-handler
  "PUT /api/resources/:id/scope — set the resource's :scope field. Body
  field :scope must be one of db/valid-scopes (\"private\", \"both\", or
  \"work\"). Returns 200 with the updated row, 400 {:error} on an invalid
  value, or 404 {:error} when the resource does not exist or is not owned
  by the caller. Records an :update event with the field-level diff."
  (common/make-entity-property-handler :scope db/valid-scopes
                                       "Invalid scope. Must be 'private', 'both', or 'work'"
                                       {:entity-type :resource
                                        :set-fn db.resource/set-resource-field
                                        :table :resources}))

(def set-resource-importance-handler
  "PUT /api/resources/:id/importance — set the resource's :importance
  field. Body field :importance must be one of db/valid-importances
  (\"normal\", \"important\", or \"critical\"). Returns 200 with the updated
  row, 400 {:error} on invalid input, or 404 {:error} when the resource is
  not found. Records an :update event with the field-level diff."
  (common/make-entity-property-handler :importance db/valid-importances
                                       "Invalid importance. Must be 'normal', 'important', or 'critical'"
                                       {:entity-type :resource
                                        :set-fn db.resource/set-resource-field
                                        :table :resources}))
