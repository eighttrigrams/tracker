(ns et.tr.server.journal-handler
  (:require [et.tr.server.common :as common]
            [et.tr.server.events :as events]
            [et.tr.db :as db]
            [et.tr.db.journal :as db.journal]
            [clojure.string :as str]))

(defn get-journal-handler
  "GET /api/journals/:id — fetch a single journal by id for the current user.
  Parses :id as an integer. Returns the journal row on 200, or 404 with
  {:error \"Journal not found\"} when absent."
  [req]
  (let [user-id (common/get-user-id req)
        journal-id (Integer/parseInt (get-in req [:params :id]))]
    (if-let [journal (db.journal/get-journal (common/ensure-ds) user-id journal-id)]
      {:status 200 :body journal}
      {:status 404 :body {:error "Journal not found"}})))

(defn list-journals-handler
  "GET /api/journals/ — list journals for the current user. Query params: q
  (search term), context, strict (\"true\"/\"false\"), comma-separated category
  id lists people/places/projects/goals, and limit (int — caps the row count;
  machine users default to 10 when omitted). Categories are only forwarded to
  the query when at least one list is provided. Always returns 200 with the
  result vector."
  [req]
  (let [user-id (common/get-user-id req)
        search-term (get-in req [:params "q"])
        context (get-in req [:params "context"])
        strict (= "true" (get-in req [:params "strict"]))
        people (common/parse-category-param (get-in req [:params "people"]))
        places (common/parse-category-param (get-in req [:params "places"]))
        projects (common/parse-category-param (get-in req [:params "projects"]))
        goals (common/parse-category-param (get-in req [:params "goals"]))
        limit (common/parse-int-opt (get-in req [:params "limit"]))
        categories (when (or people places projects goals)
                     {:people people :places places :projects projects :goals goals})]
    {:status 200 :body (db.journal/list-journals (common/ensure-ds) user-id {:search-term search-term :context context :strict strict :categories categories :limit limit})}))

(defn add-journal-handler
  "POST /api/journals/ — create a journal. Body fields: :title (required,
  non-blank), :scope (one of private/both/work, defaults to \"both\") and
  :schedule-type (defaults to \"daily\"). Returns 400 {:success false :error
  \"Title is required\"} when the title is blank, otherwise 201 with the
  created row and records a :journal create event."
  [req]
  (let [user-id (common/get-user-id req)
        {:keys [title scope schedule-type]} (:body req)]
    (if (str/blank? title)
      {:status 400 :body {:success false :error "Title is required"}}
      (let [journal (db.journal/add-journal (common/ensure-ds) user-id title (or scope "both") (or schedule-type "daily"))]
        (events/record-create! req :journal (:id journal) journal)
        {:status 201 :body journal}))))

(defn update-journal-handler
  "PUT /api/journals/:id — update title/description/tags on a journal. Body
  fields: :title (required, non-blank), :description and :tags (default to
  empty strings). Returns 400 {:success false :error \"Title is required\"}
  for a blank title, otherwise 200 with the updated row and records a
  :journal update event with before/after field snapshots."
  [req]
  (let [user-id (common/get-user-id req)
        journal-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [title description tags]} (:body req)]
    (if (str/blank? title)
      {:status 400 :body {:success false :error "Title is required"}}
      (let [expected (get-in req [:body :expected-modified-at])
            before (events/fetch-fields :journals journal-id [:title :description :tags])
            result (db.journal/update-journal (common/ensure-ds) user-id journal-id {:title title :description (or description "") :tags (or tags "")} expected)]
        (if result
          (do (events/record-update! req :journal journal-id before
                                     (select-keys result [:title :description :tags]))
              {:status 200 :body result})
          (common/conflict-or-not-found (db.journal/get-journal (common/ensure-ds) user-id journal-id) "Journal not found"))))))

(defn delete-journal-handler
  "DELETE /api/journals/:id — delete a journal owned by the current user.
  Snapshots the row first, then on success records a :journal delete event
  and returns 200 {:success true}. Returns 404 {:success false :error
  \"Journal not found\"} when no row was removed."
  [req]
  (let [user-id (common/get-user-id req)
        journal-id (Integer/parseInt (get-in req [:params :id]))
        snapshot (events/fetch-row :journals journal-id)
        result (db.journal/delete-journal (common/ensure-ds) user-id journal-id)]
    (if (:success result)
      (do (events/record-delete! req :journal journal-id snapshot)
          {:status 200 :body {:success true}})
      {:status 404 :body {:success false :error "Journal not found"}})))

(def categorize-journal-handler
  "POST /api/journals/:id/categorize — attach a category (person, place,
  project, or goal) to a journal. Body fields per
  common/make-categorize-handler: :category-type and :category-id. Returns
  the shared categorize response shape and records a :journal event."
  (common/make-categorize-handler db.journal/categorize-journal :journal))

(def uncategorize-journal-handler
  "DELETE /api/journals/:id/categorize — detach a category from a journal.
  Body fields: :category-type and :category-id. Returns the shared
  uncategorize response shape and records a :journal event."
  (common/make-uncategorize-handler db.journal/uncategorize-journal :journal))

(def set-journal-scope-handler
  "PUT /api/journals/:id/scope — change the scope of a journal. Body field
  :scope must be one of db/valid-scopes (private/both/work); invalid values
  yield 400 {:error \"Invalid scope. Must be 'private', 'both', or 'work'\"}.
  On success returns the shared property-update shape and records a :journal
  update event."
  (common/make-entity-property-handler :scope db/valid-scopes
                                       "Invalid scope. Must be 'private', 'both', or 'work'"
                                       {:entity-type :journal
                                        :set-fn db.journal/set-journal-field
                                        :table :journals}))

(defn get-taken-dates-handler
  "GET /api/journals/:id/taken-dates — return dates already occupied by entries
  belonging to this journal, used by the UI to grey out unavailable slots when
  creating an entry. Returns 200 {:dates [...]}, or 404 {:error ...} if the
  journal does not exist for this user."
  [req]
  (let [user-id (common/get-user-id req)
        journal-id (Integer/parseInt (get-in req [:params :id]))]
    (if-let [dates (db.journal/get-taken-dates (common/ensure-ds) user-id journal-id)]
      {:status 200 :body {:dates dates}}
      {:status 404 :body {:error "Journal not found"}})))

(defn create-entry-handler
  "POST /api/journals/:id/create-entry — create a new journal entry under the
  given journal for a specific date. Body field :date must be in YYYY-MM-DD
  format; otherwise returns 400 {:error \"Invalid date format. Use
  YYYY-MM-DD\"}. Returns 404 {:error \"Journal not found\"} when the parent
  is missing, otherwise 201 with the created entry and a :journal-entry
  create event."
  [req]
  (let [user-id (common/get-user-id req)
        journal-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [date]} (:body req)]
    (if (not (common/valid-date-format? date))
      {:status 400 :body {:error "Invalid date format. Use YYYY-MM-DD"}}
      (if-let [entry (db.journal/create-entry-for-journal (common/ensure-ds) user-id journal-id date)]
        (do (events/record-create! req :journal-entry (:id entry) entry)
            {:status 201 :body entry})
        {:status 404 :body {:error "Journal not found"}}))))
