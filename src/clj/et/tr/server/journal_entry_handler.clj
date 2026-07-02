(ns et.tr.server.journal-entry-handler
  (:require [et.tr.server.common :as common]
            [et.tr.server.events :as events]
            [et.tr.db :as db]
            [et.tr.db.journal-entry :as db.journal-entry]
            [clojure.string :as str]))

(defn get-journal-entry-handler
  "GET /api/journal-entries/:id — fetch a single journal entry by id for the
  current user. Parses :id as an integer. Returns the entry row on 200, or
  404 with {:error \"Journal entry not found\"} when absent."
  [req]
  (let [user-id (common/get-user-id req)
        entry-id (Integer/parseInt (get-in req [:params :id]))]
    (if-let [entry (db.journal-entry/get-journal-entry (common/ensure-ds) user-id entry-id)]
      {:status 200 :body entry}
      {:status 404 :body {:error "Journal entry not found"}})))

(defn list-journal-entries-handler
  "GET /api/journal-entries/ — list journal entries for the current user.
  Query params: q (search term), importance, context, strict (\"true\"/
  \"false\"), comma-separated category id lists people/places/projects/goals,
  sortMode, journalId (parsed as an int,
  ignored if non-numeric), limit (int — caps the row count; machine users
  default to 10 when omitted). Categories are only forwarded when at least
  one list is provided. Always returns 200 with the result vector."
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
        journal-id (when-let [jid (get-in req [:params "journalId"])]
                     (try (Integer/parseInt jid) (catch Exception _ nil)))
        limit (common/parse-int-opt (get-in req [:params "limit"]))
        categories (when (or people places projects goals)
                     {:people people :places places :projects projects :goals goals})]
    {:status 200 :body (db.journal-entry/list-journal-entries (common/ensure-ds) user-id {:search-term search-term :importance importance :context context :strict strict :categories categories :sort-mode sort-mode :journal-id journal-id :limit limit})}))

(defn list-today-journal-entries-handler
  "GET /api/journal-entries/today — list today's journal entries for the
  current user. Query params: context and strict (\"true\"/\"false\"). Always
  returns 200 with the result vector."
  [req]
  (let [user-id (common/get-user-id req)
        context (get-in req [:params "context"])
        strict (= "true" (get-in req [:params "strict"]))]
    {:status 200 :body (db.journal-entry/list-today-journal-entries (common/ensure-ds) user-id {:context context :strict strict})}))

(defn add-journal-entry-handler
  "POST /api/journal-entries/ — create a journal entry. Body fields: :title
  (required, non-blank) and :scope (one of private/both/work, defaults to
  \"both\"). Returns 400 {:success false :error \"Title is required\"} when
  the title is blank, otherwise 201 with the created row and records a
  :journal-entry create event."
  [req]
  (let [user-id (common/get-user-id req)
        {:keys [title scope]} (:body req)]
    (if (str/blank? title)
      {:status 400 :body {:success false :error "Title is required"}}
      (let [entry (db.journal-entry/add-journal-entry (common/ensure-ds) user-id title (or scope "both"))]
        (events/record-create! req :journal-entry (:id entry) entry)
        {:status 201 :body entry}))))

(defn update-journal-entry-handler
  "PUT /api/journal-entries/:id — update title/description/tags on a journal
  entry. Body fields: :title (required, non-blank), :description and :tags
  (default to empty strings). Returns 400 {:success false :error \"Title is
  required\"} for a blank title, otherwise 200 with the updated row and
  records a :journal-entry update event with before/after field snapshots."
  [req]
  (let [user-id (common/get-user-id req)
        entry-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [title description tags]} (:body req)]
    (if (str/blank? title)
      {:status 400 :body {:success false :error "Title is required"}}
      (let [expected (get-in req [:body :expected-modified-at])
            before (events/fetch-fields :journal_entries entry-id [:title :description :tags])
            result (db.journal-entry/update-journal-entry (common/ensure-ds) user-id entry-id {:title title :description (or description "") :tags (or tags "")} expected)]
        (if result
          (do (events/record-update! req :journal-entry entry-id before
                                     (select-keys result [:title :description :tags]))
              {:status 200 :body result})
          (common/conflict-or-not-found (db.journal-entry/get-journal-entry (common/ensure-ds) user-id entry-id) "Journal entry not found"))))))

(defn set-journal-entry-relation-badge-title-handler
  "PUT /api/journal-entries/:id/relation-badge-title — set or clear the
  override badge title shown when this entry appears as a relation badge on
  another item. Body: {:relation-badge-title} (string; empty string clears).
  Returns the updated entry on 200, 404 if not found. Logs an :update event
  for :relation_badge_title."
  [req]
  (let [user-id (common/get-user-id req)
        entry-id (Integer/parseInt (get-in req [:params :id]))
        value (or (get-in req [:body :relation-badge-title]) "")
        before (events/fetch-fields :journal_entries entry-id [:relation_badge_title])
        result (db.journal-entry/set-journal-entry-field (common/ensure-ds) user-id entry-id :relation_badge_title value)]
    (if result
      (do (events/record-update! req :journal-entry entry-id before
                                 (select-keys result [:relation_badge_title]))
          {:status 200 :body result})
      {:status 404 :body {:error "Journal entry not found"}})))

(defn delete-journal-entry-handler
  "DELETE /api/journal-entries/:id — delete a journal entry owned by the
  current user. Snapshots the row first, then on success records a
  :journal-entry delete event and returns 200 {:success true}. Returns 404
  {:success false :error \"Journal entry not found\"} when no row was
  removed."
  [req]
  (let [user-id (common/get-user-id req)
        entry-id (Integer/parseInt (get-in req [:params :id]))
        snapshot (events/fetch-row :journal_entries entry-id)
        result (db.journal-entry/delete-journal-entry (common/ensure-ds) user-id entry-id)]
    (if (:success result)
      (do (events/record-delete! req :journal-entry entry-id snapshot)
          {:status 200 :body {:success true}})
      {:status 404 :body {:success false :error "Journal entry not found"}})))

(def categorize-journal-entry-handler
  "POST /api/journal-entries/:id/categorize — attach a category (person,
  place, project, or goal) to a journal entry. Body fields per
  common/make-categorize-handler: :category-type and :category-id. Returns
  the shared categorize response shape and records a :journal-entry event."
  (common/make-categorize-handler db.journal-entry/categorize-journal-entry :journal-entry))

(def uncategorize-journal-entry-handler
  "DELETE /api/journal-entries/:id/categorize — detach a category from a
  journal entry. Body fields: :category-type and :category-id. Returns the
  shared uncategorize response shape and records a :journal-entry event."
  (common/make-uncategorize-handler db.journal-entry/uncategorize-journal-entry :journal-entry))

(defn reorder-journal-entry-handler
  "POST /api/journal-entries/:id/reorder — move a journal entry before or
  after another entry in the manual sort order. Body fields: :target-entry-id
  (the anchor entry) and :position (\"before\" or \"after\"). Computes a new
  sort_order by averaging with the neighbour (or stepping by 1.0 at an end)
  and returns 200 {:success true :sort_order ...}."
  [req]
  (let [user-id (common/get-user-id req)
        entry-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [target-entry-id position]} (:body req)
        all-entries (db.journal-entry/list-journal-entries (common/ensure-ds) user-id {:sort-mode "manual"})
        target-idx (->> all-entries
                        (map-indexed vector)
                        (some (fn [[idx e]] (when (= (:id e) target-entry-id) idx))))
        target-order (:sort_order (nth all-entries target-idx))
        neighbor-idx (if (= position "before") (dec target-idx) (inc target-idx))
        neighbor-order (when (and (>= neighbor-idx 0) (< neighbor-idx (count all-entries)))
                         (:sort_order (nth all-entries neighbor-idx)))
        new-order (cond
                    (nil? neighbor-order)
                    (if (= position "before")
                      (- target-order 1.0)
                      (+ target-order 1.0))
                    :else
                    (/ (+ target-order neighbor-order) 2.0))]
    (db.journal-entry/reorder-journal-entry (common/ensure-ds) user-id entry-id new-order)
    {:status 200 :body {:success true :sort_order new-order}}))

(def set-journal-entry-scope-handler
  "PUT /api/journal-entries/:id/scope — change the scope of a journal entry.
  Body field :scope must be one of db/valid-scopes (private/both/work);
  invalid values yield 400 {:error \"Invalid scope. Must be 'private',
  'both', or 'work'\"}. On success returns the shared property-update shape
  and records a :journal-entry update event."
  (common/make-entity-property-handler :scope db/valid-scopes
                                       "Invalid scope. Must be 'private', 'both', or 'work'"
                                       {:entity-type :journal-entry
                                        :set-fn db.journal-entry/set-journal-entry-field
                                        :table :journal_entries}))

(def set-journal-entry-importance-handler
  "PUT /api/journal-entries/:id/importance — change the importance of a
  journal entry. Body field :importance must be one of db/valid-importances
  (normal/important/critical); invalid values yield 400 {:error \"Invalid
  importance. Must be 'normal', 'important', or 'critical'\"}. On success
  returns the shared property-update shape and records a :journal-entry
  update event."
  (common/make-entity-property-handler :importance db/valid-importances
                                       "Invalid importance. Must be 'normal', 'important', or 'critical'"
                                       {:entity-type :journal-entry
                                        :set-fn db.journal-entry/set-journal-entry-field
                                        :table :journal_entries}))
