(ns et.tr.server.issue-handler
  (:require [et.tr.server.common :as common]
            [et.tr.server.events :as events]
            [et.tr.db :as db]
            [et.tr.db.issue :as db.issue]
            [et.tr.db.task :as db.task]
            [clojure.string :as str]))

(defn get-issue-handler
  "GET /api/issues/:id — fetch a single issue owned by the caller. The :id path
  param is parsed as an integer. Returns 200 with the issue row (including its
  belonging tasks) or 404 {:error} if no row matches both the id and the
  caller's user-id."
  [req]
  (let [user-id (common/get-user-id req)
        issue-id (Integer/parseInt (get-in req [:params :id]))]
    (if-let [issue (db.issue/get-issue (common/ensure-ds) user-id issue-id)]
      {:status 200 :body issue}
      {:status 404 :body {:error "Issue not found"}})))

(defn list-issues-handler
  "GET /api/issues — list the caller's issues, filtered and sorted by query
  params. Recognised params: q (search term), importance, context, strict
  (\"true\" toggles strict context match), people/places/projects/goals
  (comma-separated category id lists), sortMode, limit (int — caps the row
  count; machine users default to 100 when omitted), offset (int),
  paged (\"true\" wraps the response as {:items :has_more}). Returns 200 with a
  vector of rows, or the {:items :has_more} envelope when paged."
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
        sort-mode (get-in req [:params "sortMode"])
        limit (common/parse-int-opt (get-in req [:params "limit"]))
        offset (common/parse-int-opt (get-in req [:params "offset"]))
        paged? (= "true" (get-in req [:params "paged"]))
        categories (when (or people places projects goals)
                     {:people people :places places :projects projects :goals goals})
        rows (vec (db.issue/list-issues (common/ensure-ds) user-id
                    {:search-term search-term :importance importance :context context :strict strict
                     :categories categories :sort-mode sort-mode
                     :limit (when limit (inc limit)) :offset offset}))
        has-more? (boolean (and limit (> (count rows) limit)))
        items (if has-more? (subvec rows 0 limit) rows)]
    {:status 200 :body (if paged? {:items items :has_more has-more?} items)}))

(defn add-issue-handler
  "POST /api/issues — create a new issue for the caller. Body fields: :title
  (required, non-blank), :scope (defaults to \"both\"). Returns 201 with the
  created row, or 400 {:success false :error} on validation failure. Records a
  :create event after a successful insert."
  [req]
  (let [user-id (common/get-user-id req)
        {:keys [title scope]} (:body req)]
    (if (str/blank? title)
      {:status 400 :body {:success false :error "Title is required"}}
      (let [issue (db.issue/add-issue (common/ensure-ds) user-id title (or scope "both"))]
        (events/record-create! req :issue (:id issue) issue)
        {:status 201 :body issue}))))

(defn update-issue-handler
  "PUT /api/issues/:id — update mutable fields on the caller's issue. Body
  fields: :title (required, non-blank), :description, :tags. Returns 200 with
  the updated row, or 400 {:success false :error} on validation failure.
  Records an :update event with the before/after diff."
  [req]
  (let [user-id (common/get-user-id req)
        issue-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [title description tags]} (:body req)]
    (if (str/blank? title)
      {:status 400 :body {:success false :error "Title is required"}}
      (let [expected (get-in req [:body :expected-modified-at])
            fields (cond-> {:title title}
                     (some? description) (assoc :description description)
                     (some? tags) (assoc :tags tags))
            before (events/fetch-fields :issues issue-id [:title :description :tags])
            issue (db.issue/update-issue (common/ensure-ds) user-id issue-id fields expected)]
        (if issue
          (do (events/record-update! req :issue issue-id before
                                     (select-keys issue [:title :description :tags]))
              {:status 200 :body issue})
          (common/conflict-or-not-found (db.issue/get-issue (common/ensure-ds) user-id issue-id) "Issue not found"))))))

(defn set-issue-relation-badge-title-handler
  "PUT /api/issues/:id/relation-badge-title — set or clear the override badge
  title shown when this issue appears as a relation badge on another item.
  Body: {:relation-badge-title} (string; empty string clears). Returns the
  updated issue on 200, 404 if not found. Logs an :update event for
  :relation_badge_title."
  [req]
  (let [user-id (common/get-user-id req)
        issue-id (Integer/parseInt (get-in req [:params :id]))
        value (or (get-in req [:body :relation-badge-title]) "")
        before (events/fetch-fields :issues issue-id [:relation_badge_title])
        result (db.issue/set-issue-field (common/ensure-ds) user-id issue-id :relation_badge_title value)]
    (if result
      (do (events/record-update! req :issue issue-id before
                                 (select-keys result [:relation_badge_title]))
          {:status 200 :body result})
      {:status 404 :body {:error "Issue not found"}})))

(defn delete-issue-handler
  "DELETE /api/issues/:id — delete the caller's issue. Detaches any tasks that
  belong to it (issue_id set to null) and removes its relations. Snapshots the
  row first so the audit log retains its contents. Returns 200 {:success true}
  on success, or 404 {:success false :error} when the row does not exist or is
  not owned by the caller. Records a :delete event with the snapshot."
  [req]
  (let [user-id (common/get-user-id req)
        issue-id (Integer/parseInt (get-in req [:params :id]))
        snapshot (events/fetch-row :issues issue-id)
        result (db.issue/delete-issue (common/ensure-ds) user-id issue-id)]
    (if (:success result)
      (do (events/record-delete! req :issue issue-id snapshot)
          {:status 200 :body {:success true}})
      {:status 404 :body {:success false :error "Issue not found"}})))

(defn create-task-for-issue-handler
  "POST /api/issues/:id/create-task — materialize a new task that belongs to
  this issue. Body field :title sets the task title; when it is blank or absent
  the task falls back to the issue's title. The task inherits the issue's scope,
  and its issue_id FK is set to the issue (mirrors the recurring-task create-task
  endpoint). Category associations are applied by the caller afterwards, the same
  way the Tasks page add form does. Returns 201 with the created task, or 404
  {:success false :error} when the issue does not exist or is not owned by the
  caller. Records a :create event for the task."
  [req]
  (let [user-id (common/get-user-id req)
        issue-id (Integer/parseInt (get-in req [:params :id]))
        title (get-in req [:body :title])]
    (if-let [issue (db.issue/get-issue (common/ensure-ds) user-id issue-id)]
      (let [title (if (str/blank? title) (:title issue) title)
            task (db.task/add-task (common/ensure-ds) user-id title (:scope issue))]
        (db.issue/set-task-issue (common/ensure-ds) user-id (:id task) issue-id)
        (let [task (db.task/get-task (common/ensure-ds) user-id (:id task))]
          (events/record-create! req :task (:id task) task)
          {:status 201 :body task}))
      {:status 404 :body {:success false :error "Issue not found"}})))

(def categorize-issue-handler
  "POST /api/issues/:id/categorize — link the issue to a category. Body fields:
  :category-type (\"person\"/\"place\"/\"project\"/\"goal\") and :category-id
  (positive integer). Returns 200 {:success true} on success, or 400 {:success
  false :error} when either field is missing or invalid. Records a :link event."
  (common/make-categorize-handler db.issue/categorize-issue :issue))

(def uncategorize-issue-handler
  "DELETE /api/issues/:id/categorize — unlink the issue from a category. Body
  fields: :category-type and :category-id, validated as for categorize. Returns
  200 {:success true} on success, or 400 {:success false :error} on bad input.
  Records an :unlink event."
  (common/make-uncategorize-handler db.issue/uncategorize-issue :issue))

(defn reorder-issue-handler
  "POST /api/issues/:id/reorder — move the issue within the caller's manual
  ordering. Body fields: :target-issue-id (the neighbour to anchor on) and
  :position (\"before\" or \"after\"). Computes a new fractional sort_order
  halfway between the target and its neighbour (or one step past the edge when
  there is none). Returns 200 {:success true :sort_order}."
  [req]
  (let [user-id (common/get-user-id req)
        issue-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [target-issue-id position]} (:body req)
        all-issues (db.issue/list-issues (common/ensure-ds) user-id {:sort-mode "manual"})
        target-idx (->> all-issues
                        (map-indexed vector)
                        (some (fn [[idx r]] (when (= (:id r) target-issue-id) idx))))
        target-order (:sort_order (nth all-issues target-idx))
        neighbor-idx (if (= position "before") (dec target-idx) (inc target-idx))
        neighbor-order (when (and (>= neighbor-idx 0) (< neighbor-idx (count all-issues)))
                         (:sort_order (nth all-issues neighbor-idx)))
        new-order (cond
                    (nil? neighbor-order)
                    (if (= position "before")
                      (- target-order 1.0)
                      (+ target-order 1.0))
                    :else
                    (/ (+ target-order neighbor-order) 2.0))]
    (db.issue/reorder-issue (common/ensure-ds) user-id issue-id new-order)
    {:status 200 :body {:success true :sort_order new-order}}))

(def set-issue-scope-handler
  "PUT /api/issues/:id/scope — set the issue's :scope field. Body field :scope
  must be one of db/valid-scopes (\"private\", \"both\", or \"work\"). Returns
  200 with the updated row, 400 {:error} on an invalid value, or 404 {:error}
  when the issue does not exist or is not owned by the caller. Records an
  :update event with the field-level diff."
  (common/make-entity-property-handler :scope db/valid-scopes
                                       "Invalid scope. Must be 'private', 'both', or 'work'"
                                       {:entity-type :issue
                                        :set-fn db.issue/set-issue-field
                                        :table :issues}))

(def set-issue-importance-handler
  "PUT /api/issues/:id/importance — set the issue's :importance field. Body
  field :importance must be one of db/valid-importances (\"normal\",
  \"important\", or \"critical\"). Returns 200 with the updated row, 400
  {:error} on invalid input, or 404 {:error} when the issue is not found.
  Records an :update event with the field-level diff."
  (common/make-entity-property-handler :importance db/valid-importances
                                       "Invalid importance. Must be 'normal', 'important', or 'critical'"
                                       {:entity-type :issue
                                        :set-fn db.issue/set-issue-field
                                        :table :issues}))

(def set-issue-urgency-handler
  "PUT /api/issues/:id/urgency — set the issue's :urgency field. Body field
  :urgency must be one of db/valid-urgencies (\"default\", \"urgent\", or
  \"superurgent\"). Returns 200 with the updated row, 400 {:error} on invalid
  input, or 404 {:error} when the issue is not found. Records an :update event
  with the field-level diff."
  (common/make-entity-property-handler :urgency db/valid-urgencies
                                       "Invalid urgency. Must be 'default', 'urgent', or 'superurgent'"
                                       {:entity-type :issue
                                        :set-fn db.issue/set-issue-field
                                        :table :issues}))
