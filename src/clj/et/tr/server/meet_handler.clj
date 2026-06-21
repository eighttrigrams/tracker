(ns et.tr.server.meet-handler
  (:require [et.tr.server.common :as common]
            [et.tr.server.events :as events]
            [et.tr.server.week-window :as week-window]
            [et.tr.db :as db]
            [et.tr.db.meet :as db.meet]
            [clojure.string :as str]))

(defn get-meet-handler
  "GET /api/meets/:id — fetch a single meet by numeric id, scoped to the calling
  user. Returns the full meet row on 200, or {:error \"Meet not found\"} with
  404 if no row matches the id for this user."
  [req]
  (let [user-id (common/get-user-id req)
        meet-id (Integer/parseInt (get-in req [:params :id]))]
    (if-let [meet (db.meet/get-meet (common/ensure-ds) user-id meet-id)]
      {:status 200 :body meet}
      {:status 404 :body {:error "Meet not found"}})))

(defn list-meets-handler
  "GET /api/meets/ — list meets for the calling user. Query params: q (search
  term), importance, context, strict (\"true\" toggles strict mode), sort
  (\"past\" | \"summary\" | default \"upcoming\"), people, places, projects,
  goals, excluded-places, excluded-projects (all CSV id lists), series-id
  (int), limit (int — caps the row count; machine users default to 10 when
  omitted), paged (\"true\" opts into the week-windowed envelope), weekOffset
  /weekLimit (units=weeks; default weekLimit=4). When paged with sort past or
  upcoming, the result is week-windowed — backward in time for past, forward
  for upcoming — and wrapped as {:items :has_more}; otherwise a bare vector is
  returned (the machine contract is unchanged). Category filters only kick in
  when at least one of people/places/projects/goals is non-empty. Returns 200
  with the matching rows."
  [req]
  (let [user-id (common/get-user-id req)
        search-term (get-in req [:params "q"])
        importance (get-in req [:params "importance"])
        context (get-in req [:params "context"])
        strict (= "true" (get-in req [:params "strict"]))
        sort-mode (case (get-in req [:params "sort"])
                    "past" :past
                    "summary" :summary
                    :upcoming)
        people (common/parse-category-param (get-in req [:params "people"]))
        places (common/parse-category-param (get-in req [:params "places"]))
        projects (common/parse-category-param (get-in req [:params "projects"]))
        goals (common/parse-category-param (get-in req [:params "goals"]))
        excluded-places (common/parse-category-param (get-in req [:params "excluded-places"]))
        excluded-projects (common/parse-category-param (get-in req [:params "excluded-projects"]))
        series-id (when-let [s (get-in req [:params "series-id"])] (Integer/parseInt s))
        limit (common/parse-int-opt (get-in req [:params "limit"]))
        categories (when (or people places projects goals)
                     {:people people :places places :projects projects :goals goals})
        paged (= "true" (get-in req [:params "paged"]))
        base-opts {:search-term search-term :importance importance :context context :strict strict :categories categories :sort-mode sort-mode :excluded-places excluded-places :excluded-projects excluded-projects :series-id series-id :limit limit}]
    (if (and paged (contains? #{:past :upcoming} sort-mode))
      (let [direction (if (= sort-mode :past) :backward :forward)
            window (week-window/week-window (week-window/parse-week-param (get-in req [:params "weekOffset"]) 0)
                                            (week-window/parse-week-param (get-in req [:params "weekLimit"]) 4)
                                            direction)
            meets (db.meet/list-meets (common/ensure-ds) user-id
                    (assoc base-opts :date-from (:date-from window) :date-to (:date-to window)))
            probe-opts (if (= direction :backward)
                         (assoc base-opts :date-to (:date-from window) :limit 1)
                         (assoc base-opts :date-from (:date-to window) :limit 1))
            has-more (boolean (seq (db.meet/list-meets (common/ensure-ds) user-id probe-opts)))]
        {:status 200 :body {:items meets :has_more has-more}})
      {:status 200 :body (db.meet/list-meets (common/ensure-ds) user-id base-opts)})))

(defn add-meet-handler
  "POST /api/meets/ — create a new meet for the calling user. Body: {:title
  :scope}. Title is required (400 with {:success false :error ...} if blank);
  scope defaults to \"both\". On success returns 201 with the created row and
  emits a :create event via events/record-create!."
  [req]
  (let [user-id (common/get-user-id req)
        {:keys [title scope]} (:body req)]
    (if (str/blank? title)
      {:status 400 :body {:success false :error "Title is required"}}
      (let [meet (db.meet/add-meet (common/ensure-ds) user-id title (or scope "both"))]
        (events/record-create! req :meet (:id meet) meet)
        {:status 201 :body meet}))))

(defn update-meet-handler
  "PUT /api/meets/:id — update the title, description, and tags of a meet.
  Body: {:title :description :tags}. Title is required (400 if blank);
  description and tags default to \"\" when missing. Captures the prior values
  before the write and emits an :update event with before/after diffs."
  [req]
  (let [user-id (common/get-user-id req)
        meet-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [title description tags]} (:body req)]
    (if (str/blank? title)
      {:status 400 :body {:success false :error "Title is required"}}
      (let [before (events/fetch-fields :meets meet-id [:title :description :tags])
            result (db.meet/update-meet (common/ensure-ds) user-id meet-id {:title title :description (or description "") :tags (or tags "")})]
        (events/record-update! req :meet meet-id before
                               (select-keys result [:title :description :tags]))
        {:status 200 :body result}))))

(defn delete-meet-handler
  "DELETE /api/meets/:id — delete a meet owned by the calling user. Snapshots
  the row first so the :delete event payload includes the deleted state.
  Returns 200 {:success true} on success, or 404 {:success false :error ...}
  if no row was deleted."
  [req]
  (let [user-id (common/get-user-id req)
        meet-id (Integer/parseInt (get-in req [:params :id]))
        snapshot (events/fetch-row :meets meet-id)
        result (db.meet/delete-meet (common/ensure-ds) user-id meet-id)]
    (if (:success result)
      (do (events/record-delete! req :meet meet-id snapshot)
          {:status 200 :body {:success true}})
      {:status 404 :body {:success false :error "Meet not found"}})))

(defn set-meet-relation-badge-title-handler
  "PUT /api/meets/:id/relation-badge-title — set or clear the override badge
  title shown when this meet appears as a relation badge on another item.
  Body: {:relation-badge-title} (string; empty string clears). Returns the
  updated meet on 200, 404 if not found. Logs an :update event for
  :relation_badge_title."
  [req]
  (let [user-id (common/get-user-id req)
        meet-id (Integer/parseInt (get-in req [:params :id]))
        value (or (get-in req [:body :relation-badge-title]) "")
        before (events/fetch-fields :meets meet-id [:relation_badge_title])
        result (db.meet/set-meet-field (common/ensure-ds) user-id meet-id :relation_badge_title value)]
    (if result
      (do (events/record-update! req :meet meet-id before
                                 (select-keys result [:relation_badge_title]))
          {:status 200 :body result})
      {:status 404 :body {:error "Meet not found"}})))

(defn archive-meet-handler
  "PUT /api/meets/:id/archive — flip the archived flag on a meet. No body is
  read; the underlying db.meet/archive-meet decides the new value. Returns 200
  with the updated row (and emits an :update event diffing :archived) or 404
  {:error \"Meet not found\"} if the meet does not exist for this user."
  [req]
  (let [user-id (common/get-user-id req)
        meet-id (Integer/parseInt (get-in req [:params :id]))
        before (events/fetch-fields :meets meet-id [:archived])]
    (if-let [result (db.meet/archive-meet (common/ensure-ds) user-id meet-id)]
      (do (events/record-update! req :meet meet-id before
                                 (select-keys result [:archived]))
          {:status 200 :body result})
      {:status 404 :body {:error "Meet not found"}})))

(def categorize-meet-handler
  "POST /api/meets/:id/categorize — link a meet to a category. Body:
  {:category-type :category-id}. category-type must be a non-blank string
  (\"person\" | \"place\" | \"project\" | \"goal\") and category-id a positive
  integer; otherwise 400 with {:success false :error ...}. On success returns
  200 {:success true} and emits a :link event."
  (common/make-categorize-handler db.meet/categorize-meet :meet))

(def uncategorize-meet-handler
  "DELETE /api/meets/:id/categorize — remove a category link from a meet.
  Body: {:category-type :category-id}, validated identically to the categorize
  handler (400 on bad input). On success returns 200 {:success true} and emits
  an :unlink event."
  (common/make-uncategorize-handler db.meet/uncategorize-meet :meet))

(def set-meet-scope-handler
  "PUT /api/meets/:id/scope — set a meet's :scope. Body: {:scope}. Value must
  be one of db/valid-scopes (\"private\", \"both\", \"work\"); otherwise 400
  with {:error \"Invalid scope...\"}. Returns 200 with the updated row and
  emits an :update event diffing :scope, or 404 if the meet does not exist."
  (common/make-entity-property-handler :scope db/valid-scopes
                                       "Invalid scope. Must be 'private', 'both', or 'work'"
                                       {:entity-type :meet :set-fn db.meet/set-meet-field
                                        :table :meets}))

(def set-meet-importance-handler
  "PUT /api/meets/:id/importance — set a meet's :importance. Body:
  {:importance}. Value must be one of db/valid-importances (\"normal\",
  \"important\", \"critical\"); otherwise 400 with {:error \"Invalid
  importance...\"}. Returns 200 with the updated row and emits an :update
  event diffing :importance, or 404 if the meet does not exist."
  (common/make-entity-property-handler :importance db/valid-importances
                                       "Invalid importance. Must be 'normal', 'important', or 'critical'"
                                       {:entity-type :meet :set-fn db.meet/set-meet-field
                                        :table :meets}))

(defn set-meet-start-date-handler
  "PUT /api/meets/:id/start-date — set the start date of a meet. Body:
  {:start-date}. Must match common/valid-date-format? (YYYY-MM-DD), otherwise
  400 {:error \"Invalid date format...\"}. On success returns 200 with the
  updated row and emits an :update event diffing :start_date."
  [req]
  (let [user-id (common/get-user-id req)
        meet-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [start-date]} (:body req)]
    (if (common/valid-date-format? start-date)
      (let [before (events/fetch-fields :meets meet-id [:start_date])
            result (db.meet/set-meet-start-date (common/ensure-ds) user-id meet-id start-date)]
        (events/record-update! req :meet meet-id before (select-keys result [:start_date]))
        {:status 200 :body result})
      {:status 400 :body {:error "Invalid date format. Use YYYY-MM-DD"}})))

(defn set-meet-start-time-handler
  "PUT /api/meets/:id/start-time — set the start time of a meet. Body:
  {:start-time}. Must match common/valid-time-format? (HH:MM, 24-hour),
  otherwise 400 {:error \"Invalid time format...\"}. On success returns 200
  with the updated row and emits an :update event diffing :start_time."
  [req]
  (let [user-id (common/get-user-id req)
        meet-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [start-time]} (:body req)]
    (if (common/valid-time-format? start-time)
      (let [before (events/fetch-fields :meets meet-id [:start_time])
            result (db.meet/set-meet-start-time (common/ensure-ds) user-id meet-id start-time)]
        (events/record-update! req :meet meet-id before (select-keys result [:start_time]))
        {:status 200 :body result})
      {:status 400 :body {:error "Invalid time format. Use HH:MM (24-hour format)"}})))
