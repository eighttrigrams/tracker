(ns et.tr.server.recurring-task-handler
  (:require [et.tr.server.common :as common]
            [et.tr.server.events :as events]
            [et.tr.db :as db]
            [et.tr.db.recurring-task :as db.recurring-task]
            [clojure.string :as str]))

(defn get-recurring-task-handler
  "GET /api/recurring-tasks/:id — fetch a single recurring task by id for the
  current user. Parses :id as an integer. Returns the task row on 200, or 404
  with {:error \"Recurring task not found\"} when absent."
  [req]
  (let [user-id (common/get-user-id req)
        rtask-id (Integer/parseInt (get-in req [:params :id]))]
    (if-let [rtask (db.recurring-task/get-recurring-task (common/ensure-ds) user-id rtask-id)]
      {:status 200 :body rtask}
      {:status 404 :body {:error "Recurring task not found"}})))

(defn list-recurring-tasks-handler
  "GET /api/recurring-tasks/ — list recurring tasks for the current user.
  Query params: q (search term), context, strict (\"true\"/\"false\"),
  comma-separated category id lists people/places/projects/goals, and limit
  (int — caps the row count; machine users default to 10 when omitted).
  Categories are only forwarded to the query when at least one list is
  provided. Always returns 200 with the result vector."
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
    {:status 200 :body (db.recurring-task/list-recurring-tasks (common/ensure-ds) user-id {:search-term search-term :context context :strict strict :categories categories :limit limit})}))

(defn add-recurring-task-handler
  "POST /api/recurring-tasks/ — create a recurring task. Body fields: :title
  (required, non-blank) and :scope (one of private/both/work, defaults to
  \"both\"). Returns 400 {:success false :error \"Title is required\"} when
  the title is blank, otherwise 201 with the created row and records a
  :recurring-task create event."
  [req]
  (let [user-id (common/get-user-id req)
        {:keys [title scope]} (:body req)]
    (if (str/blank? title)
      {:status 400 :body {:success false :error "Title is required"}}
      (let [rt (db.recurring-task/add-recurring-task (common/ensure-ds) user-id title (or scope "both"))]
        (events/record-create! req :recurring-task (:id rt) rt)
        {:status 201 :body rt}))))

(defn update-recurring-task-handler
  "PUT /api/recurring-tasks/:id — update title/description/tags on a recurring
  task. Body fields: :title (required, non-blank), :description and :tags
  (default to empty strings). Returns 400 {:success false :error \"Title is
  required\"} for a blank title, otherwise 200 with the updated row and
  records a :recurring-task update event with before/after field snapshots."
  [req]
  (let [user-id (common/get-user-id req)
        rtask-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [title description tags]} (:body req)]
    (if (str/blank? title)
      {:status 400 :body {:success false :error "Title is required"}}
      (let [expected (get-in req [:body :expected-modified-at])
            before (events/fetch-fields :recurring_tasks rtask-id [:title :description :tags])
            result (db.recurring-task/update-recurring-task (common/ensure-ds) user-id rtask-id {:title title :description (or description "") :tags (or tags "")} expected)]
        (if result
          (do (events/record-update! req :recurring-task rtask-id before
                                     (select-keys result [:title :description :tags]))
              {:status 200 :body result})
          (common/conflict-or-not-found (db.recurring-task/get-recurring-task (common/ensure-ds) user-id rtask-id) "Recurring task not found"))))))

(defn delete-recurring-task-handler
  "DELETE /api/recurring-tasks/:id — delete a recurring task owned by the
  current user. Snapshots the row first, then on success records a
  :recurring-task delete event and returns 200 {:success true}. Returns 404
  {:success false :error \"Recurring task not found\"} when no row was
  removed."
  [req]
  (let [user-id (common/get-user-id req)
        rtask-id (Integer/parseInt (get-in req [:params :id]))
        snapshot (events/fetch-row :recurring_tasks rtask-id)
        result (db.recurring-task/delete-recurring-task (common/ensure-ds) user-id rtask-id)]
    (if (:success result)
      (do (events/record-delete! req :recurring-task rtask-id snapshot)
          {:status 200 :body {:success true}})
      {:status 404 :body {:success false :error "Recurring task not found"}})))

(defn create-next-task-handler
  "POST /api/recurring-tasks/:id/create-task — materialize the next concrete
  task for a recurring task. Body fields: :date (YYYY-MM-DD) and :time (HH:MM
  24-hour). Returns 400 {:error ...} for invalid date or time formats, 404
  {:error \"Recurring task not found\"} when the recurring task is missing,
  otherwise 201 with the created task and a :task create event."
  [req]
  (let [user-id (common/get-user-id req)
        rtask-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [date time]} (:body req)]
    (cond
      (not (common/valid-date-format? date))
      {:status 400 :body {:error "Invalid date format. Use YYYY-MM-DD"}}

      (not (common/valid-time-format? time))
      {:status 400 :body {:error "Invalid time format. Use HH:MM (24-hour format)"}}

      :else
      (if-let [task (db.recurring-task/create-task-for-recurring (common/ensure-ds) user-id rtask-id date time)]
        (do (events/record-create! req :task (:id task) task)
            {:status 201 :body task})
        {:status 404 :body {:error "Recurring task not found"}}))))

(defn get-taken-dates-handler
  "GET /api/recurring-tasks/:id/taken-dates — list the dates already used by
  tasks generated from this recurring task, so the UI can grey them out.
  Returns 200 {:dates [...]} on success or 404 {:error \"Recurring task not
  found\"} when the recurring task is missing."
  [req]
  (let [user-id (common/get-user-id req)
        rtask-id (Integer/parseInt (get-in req [:params :id]))]
    (if-let [dates (db.recurring-task/get-taken-dates (common/ensure-ds) user-id rtask-id)]
      {:status 200 :body {:dates dates}}
      {:status 404 :body {:error "Recurring task not found"}})))

(def categorize-recurring-task-handler
  "POST /api/recurring-tasks/:id/categorize — attach a category (person, place,
  project, or goal) to a recurring task. Body fields per
  common/make-categorize-handler: :category-type and :category-id. Returns the
  shared categorize response shape and records a :recurring-task event."
  (common/make-categorize-handler db.recurring-task/categorize-recurring-task :recurring-task))

(def uncategorize-recurring-task-handler
  "DELETE /api/recurring-tasks/:id/categorize — detach a category from a
  recurring task. Body fields: :category-type and :category-id. Returns the
  shared uncategorize response shape and records a :recurring-task event."
  (common/make-uncategorize-handler db.recurring-task/uncategorize-recurring-task :recurring-task))

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

(defn set-recurring-task-schedule-handler
  "PUT /api/recurring-tasks/:id/schedule — set the scheduling rule for a
  recurring task. Body fields: :schedule-days, :schedule-time (either a single
  HH:MM value or a comma-separated list of \"day=HH:MM\" pairs with day in
  1..7), :schedule-mode, :biweekly-offset and :task-type. Returns 400 {:error
  \"Invalid time format\"} for malformed times, 404 when the recurring task is
  missing, otherwise 200 with the updated row and a :recurring-task update
  event."
  [req]
  (let [user-id (common/get-user-id req)
        rtask-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [schedule-days schedule-time schedule-mode biweekly-offset task-type]} (:body req)]
    (if (not (valid-schedule-time? schedule-time))
      {:status 400 :body {:error "Invalid time format"}}
      (let [before (events/fetch-fields :recurring_tasks rtask-id
                                        [:schedule_days :schedule_time :schedule_mode :biweekly_offset :task_type])]
        (if-let [result (db.recurring-task/set-recurring-task-schedule (common/ensure-ds) user-id rtask-id schedule-days schedule-time schedule-mode biweekly-offset task-type)]
          (do (events/record-update! req :recurring-task rtask-id before
                                     (select-keys result [:schedule_days :schedule_time :schedule_mode :biweekly_offset :task_type]))
              {:status 200 :body result})
          {:status 404 :body {:error "Recurring task not found"}})))))

(def set-recurring-task-scope-handler
  "PUT /api/recurring-tasks/:id/scope — change the scope of a recurring task.
  Body field :scope must be one of db/valid-scopes (private/both/work);
  invalid values yield 400 {:error \"Invalid scope. Must be 'private', 'both',
  or 'work'\"}. On success returns the shared property-update shape and
  records a :recurring-task update event."
  (common/make-entity-property-handler :scope db/valid-scopes
                                       "Invalid scope. Must be 'private', 'both', or 'work'"
                                       {:entity-type :recurring-task
                                        :set-fn db.recurring-task/set-recurring-task-field
                                        :table :recurring_tasks}))
