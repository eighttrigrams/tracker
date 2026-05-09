(ns et.tr.server.meeting-series-handler
  (:require [et.tr.server.common :as common]
            [et.tr.server.events :as events]
            [et.tr.db :as db]
            [et.tr.db.meeting-series :as db.meeting-series]
            [clojure.string :as str]))

(defn get-meeting-series-handler
  "GET /api/meeting-series/:id — fetch a single meeting series by numeric id,
  scoped to the calling user. Returns the full row on 200, or {:error
  \"Meeting series not found\"} with 404 if no row matches for this user."
  [req]
  (let [user-id (common/get-user-id req)
        series-id (Integer/parseInt (get-in req [:params :id]))]
    (if-let [series (db.meeting-series/get-meeting-series (common/ensure-ds) user-id series-id)]
      {:status 200 :body series}
      {:status 404 :body {:error "Meeting series not found"}})))

(defn list-meeting-series-handler
  "GET /api/meeting-series/ — list meeting series for the calling user. Query
  params: q (search term), context, strict (\"true\" toggles strict mode), and
  CSV id lists people, places, projects, goals. Category filters only kick in
  when at least one of people/places/projects/goals is non-empty. Returns 200
  with the matching rows."
  [req]
  (let [user-id (common/get-user-id req)
        search-term (get-in req [:params "q"])
        context (get-in req [:params "context"])
        strict (= "true" (get-in req [:params "strict"]))
        people (common/parse-category-param (get-in req [:params "people"]))
        places (common/parse-category-param (get-in req [:params "places"]))
        projects (common/parse-category-param (get-in req [:params "projects"]))
        goals (common/parse-category-param (get-in req [:params "goals"]))
        categories (when (or people places projects goals)
                     {:people people :places places :projects projects :goals goals})]
    {:status 200 :body (db.meeting-series/list-meeting-series (common/ensure-ds) user-id {:search-term search-term :context context :strict strict :categories categories})}))

(defn add-meeting-series-handler
  "POST /api/meeting-series/ — create a new meeting series for the calling
  user. Body: {:title :scope}. Title is required (400 with {:success false
  :error ...} if blank); scope defaults to \"both\". On success returns 201
  with the created row and emits a :create event via events/record-create!."
  [req]
  (let [user-id (common/get-user-id req)
        {:keys [title scope]} (:body req)]
    (if (str/blank? title)
      {:status 400 :body {:success false :error "Title is required"}}
      (let [series (db.meeting-series/add-meeting-series (common/ensure-ds) user-id title (or scope "both"))]
        (events/record-create! req :meeting-series (:id series) series)
        {:status 201 :body series}))))

(defn update-meeting-series-handler
  "PUT /api/meeting-series/:id — update the title, description, and tags of a
  meeting series. Body: {:title :description :tags}. Title is required (400 if
  blank); description and tags default to \"\" when missing. Captures the
  prior values before the write and emits an :update event with before/after
  diffs."
  [req]
  (let [user-id (common/get-user-id req)
        series-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [title description tags]} (:body req)]
    (if (str/blank? title)
      {:status 400 :body {:success false :error "Title is required"}}
      (let [before (events/fetch-fields :meeting_series series-id [:title :description :tags])
            result (db.meeting-series/update-meeting-series (common/ensure-ds) user-id series-id {:title title :description (or description "") :tags (or tags "")})]
        (events/record-update! req :meeting-series series-id before
                               (select-keys result [:title :description :tags]))
        {:status 200 :body result}))))

(defn delete-meeting-series-handler
  "DELETE /api/meeting-series/:id — delete a meeting series owned by the
  calling user. Snapshots the row first so the :delete event payload includes
  the deleted state. Returns 200 {:success true} on success, or 404 {:success
  false :error ...} if no row was deleted."
  [req]
  (let [user-id (common/get-user-id req)
        series-id (Integer/parseInt (get-in req [:params :id]))
        snapshot (events/fetch-row :meeting_series series-id)
        result (db.meeting-series/delete-meeting-series (common/ensure-ds) user-id series-id)]
    (if (:success result)
      (do (events/record-delete! req :meeting-series series-id snapshot)
          {:status 200 :body {:success true}})
      {:status 404 :body {:success false :error "Meeting series not found"}})))

(defn create-next-meeting-handler
  "POST /api/meeting-series/:id/create-meeting — spawn a new meet from this
  series at the given date/time. Body: {:date :time}. Both must validate via
  common/valid-date-format? (YYYY-MM-DD) and common/valid-time-format? (HH:MM,
  24-hour); otherwise 400 with the appropriate {:error ...}. On success
  returns 201 with the created meet and emits a :create event for :meet; 404
  if the series does not exist for this user."
  [req]
  (let [user-id (common/get-user-id req)
        series-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [date time]} (:body req)]
    (cond
      (not (common/valid-date-format? date))
      {:status 400 :body {:error "Invalid date format. Use YYYY-MM-DD"}}

      (not (common/valid-time-format? time))
      {:status 400 :body {:error "Invalid time format. Use HH:MM (24-hour format)"}}

      :else
      (if-let [meet (db.meeting-series/create-meeting-for-series (common/ensure-ds) user-id series-id date time)]
        (do (events/record-create! req :meet (:id meet) meet)
            {:status 201 :body meet})
        {:status 404 :body {:error "Meeting series not found"}}))))

(defn get-taken-dates-handler
  "GET /api/meeting-series/:id/taken-dates — return dates already occupied by
  meets belonging to this series, used by the UI to grey out unavailable
  slots when scheduling. Returns 200 {:dates [...]}, or 404 {:error ...} if
  the series does not exist for this user."
  [req]
  (let [user-id (common/get-user-id req)
        series-id (Integer/parseInt (get-in req [:params :id]))]
    (if-let [dates (db.meeting-series/get-taken-dates (common/ensure-ds) user-id series-id)]
      {:status 200 :body {:dates dates}}
      {:status 404 :body {:error "Meeting series not found"}})))

(def categorize-meeting-series-handler
  "POST /api/meeting-series/:id/categorize — link a meeting series to a
  category. Body: {:category-type :category-id}. category-type must be a
  non-blank string (\"person\" | \"place\" | \"project\" | \"goal\") and
  category-id a positive integer; otherwise 400 with {:success false :error
  ...}. On success returns 200 {:success true} and emits a :link event."
  (common/make-categorize-handler db.meeting-series/categorize-meeting-series :meeting-series))

(def uncategorize-meeting-series-handler
  "DELETE /api/meeting-series/:id/categorize — remove a category link from a
  meeting series. Body: {:category-type :category-id}, validated identically
  to the categorize handler (400 on bad input). On success returns 200
  {:success true} and emits an :unlink event."
  (common/make-uncategorize-handler db.meeting-series/uncategorize-meeting-series :meeting-series))

(defn- valid-schedule-time? [schedule-time]
  (or (nil? schedule-time)
      (str/blank? schedule-time)
      (common/valid-time-format? schedule-time)
      (every? (fn [pair]
                (let [parts (str/split pair #"=" 2)]
                  (and (= 2 (count parts))
                       (re-matches #"[1-7]" (first parts))
                       (common/valid-time-format? (second parts)))))
              (str/split schedule-time #","))))

(defn set-meeting-series-schedule-handler
  "PUT /api/meeting-series/:id/schedule — update the recurring schedule of a
  series. Body: {:schedule-days :schedule-time :schedule-mode
  :biweekly-offset}. schedule-time accepts nil, a blank string, a single HH:MM
  value, or a comma-separated list of \"D=HH:MM\" pairs where D is 1-7;
  invalid input yields 400 {:error \"Invalid time format\"}. On success
  returns 200 with the updated row and emits an :update event diffing the
  four schedule fields. 404 if the series does not exist for this user."
  [req]
  (let [user-id (common/get-user-id req)
        series-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [schedule-days schedule-time schedule-mode biweekly-offset]} (:body req)]
    (if (not (valid-schedule-time? schedule-time))
      {:status 400 :body {:error "Invalid time format"}}
      (let [before (events/fetch-fields :meeting_series series-id
                                        [:schedule_days :schedule_time :schedule_mode :biweekly_offset])]
        (if-let [result (db.meeting-series/set-meeting-series-schedule (common/ensure-ds) user-id series-id schedule-days schedule-time schedule-mode biweekly-offset)]
          (do (events/record-update! req :meeting-series series-id before
                                     (select-keys result [:schedule_days :schedule_time :schedule_mode :biweekly_offset]))
              {:status 200 :body result})
          {:status 404 :body {:error "Meeting series not found"}})))))

(def set-meeting-series-scope-handler
  "PUT /api/meeting-series/:id/scope — set a meeting series' :scope. Body:
  {:scope}. Value must be one of db/valid-scopes (\"private\", \"both\",
  \"work\"); otherwise 400 with {:error \"Invalid scope...\"}. Returns 200
  with the updated row and emits an :update event diffing :scope, or 404 if
  the series does not exist."
  (common/make-entity-property-handler :scope db/valid-scopes
                                       "Invalid scope. Must be 'private', 'both', or 'work'"
                                       {:entity-type :meeting-series
                                        :set-fn db.meeting-series/set-meeting-series-field
                                        :table :meeting_series}))
