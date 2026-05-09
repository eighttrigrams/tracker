(ns et.tr.server.task-handler
  (:require [et.tr.server.common :as common]
            [et.tr.server.events :as events]
            [et.tr.db :as db]
            [et.tr.db.task :as db.task]
            [clojure.string :as str]))

(defn get-task-handler
  "GET /api/tasks/:id — fetch a single task owned by the current user. Returns
  the task row on 200, or 404 if no such task exists for this user."
  [req]
  (let [user-id (common/get-user-id req)
        task-id (Integer/parseInt (get-in req [:params :id]))]
    (if-let [task (db.task/get-task (common/ensure-ds) user-id task-id)]
      {:status 200 :body task}
      {:status 404 :body {:error "Task not found"}})))

(defn list-tasks-handler
  "GET /api/tasks/?sort=&q=&importance=&context=&strict=&people=&places=&projects=&goals=&excluded-places=&excluded-projects=&recurring-task-id=
  — list tasks for the current user. Query: sort defaults to `recent`; q is a
  free-text search; people/places/projects/goals/excluded-* are CSV ids;
  strict=true requires every category to match; recurring-task-id filters
  to instances of one recurring task. Returns the task list as the body."
  [req]
  (let [user-id (common/get-user-id req)
        sort-mode (keyword (get-in req [:params "sort"] "recent"))
        search-term (get-in req [:params "q"])
        importance (get-in req [:params "importance"])
        context (get-in req [:params "context"])
        strict (= "true" (get-in req [:params "strict"]))
        people (common/parse-category-param (get-in req [:params "people"]))
        places (common/parse-category-param (get-in req [:params "places"]))
        projects (common/parse-category-param (get-in req [:params "projects"]))
        goals (common/parse-category-param (get-in req [:params "goals"]))
        excluded-places (common/parse-category-param (get-in req [:params "excluded-places"]))
        excluded-projects (common/parse-category-param (get-in req [:params "excluded-projects"]))
        recurring-task-id (when-let [s (get-in req [:params "recurring-task-id"])] (Integer/parseInt s))
        categories (when (or people places projects goals)
                     {:people people :places places :projects projects :goals goals})]
    {:status 200 :body (db.task/list-tasks (common/ensure-ds) user-id sort-mode {:search-term search-term :importance importance :context context :strict strict :categories categories :excluded-places excluded-places :excluded-projects excluded-projects :recurring-task-id recurring-task-id})}))

(defn add-task-handler
  "POST /api/tasks/ — create a new task for the current user. Body: {:title
  :scope}. `scope` defaults to \"both\". 400 if title is blank, 201 with the
  new task (plus empty :people/:places/:projects/:goals) on success. Logs a
  :create event."
  [req]
  (let [user-id (common/get-user-id req)
        {:keys [title scope]} (:body req)]
    (if (str/blank? title)
      {:status 400 :body {:success false :error "Title is required"}}
      (let [task (db.task/add-task (common/ensure-ds) user-id title (or scope "both"))]
        (events/record-create! req :task (:id task) task)
        {:status 201 :body (assoc task :people [] :places [] :projects [] :goals [])}))))

(defn update-task-handler
  "PUT /api/tasks/:id — update a task's editable text fields. Body: {:title
  :description :tags}. 400 if title is blank; description and tags default to
  empty strings. Returns the updated row on 200 and logs an :update event
  diffing :title/:description/:tags."
  [req]
  (let [user-id (common/get-user-id req)
        task-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [title description tags]} (:body req)]
    (if (str/blank? title)
      {:status 400 :body {:success false :error "Title is required"}}
      (let [before (events/fetch-fields :tasks task-id [:title :description :tags])
            task (db.task/update-task (common/ensure-ds) user-id task-id {:title title :description (or description "") :tags (or tags "")})]
        (events/record-update! req :task task-id before
                               (select-keys task [:title :description :tags]))
        {:status 200 :body task}))))

(defn categorize-task-handler
  "POST /api/tasks/:id/categorize — link a task to a person/place/project/goal.
  Body: {:category-type :category-id}. 400 if category-type is blank or
  category-id is not a positive integer. On success returns {:success true}
  and logs a :link event with the resolved category title."
  [req]
  (let [user-id (common/get-user-id req)
        task-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [category-type category-id]} (:body req)]
    (cond
      (or (nil? category-type) (str/blank? category-type))
      {:status 400 :body {:success false :error "category-type is required"}}

      (or (nil? category-id) (not (integer? category-id)) (< category-id 1))
      {:status 400 :body {:success false :error "category-id must be a positive integer"}}

      :else
      (do (db.task/categorize-task (common/ensure-ds) user-id task-id category-type category-id)
          (events/record-link! req :task task-id category-type category-id
                               (events/fetch-category-title category-type category-id))
          {:status 200 :body {:success true}}))))

(defn uncategorize-task-handler
  "DELETE /api/tasks/:id/categorize — remove a task's link to a single
  person/place/project/goal. Body: {:category-type :category-id} (same
  validation as POST /categorize). Returns {:success true} on 200 and logs
  an :unlink event."
  [req]
  (let [user-id (common/get-user-id req)
        task-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [category-type category-id]} (:body req)]
    (cond
      (or (nil? category-type) (str/blank? category-type))
      {:status 400 :body {:success false :error "category-type is required"}}

      (or (nil? category-id) (not (integer? category-id)) (< category-id 1))
      {:status 400 :body {:success false :error "category-id must be a positive integer"}}

      :else
      (do (db.task/uncategorize-task (common/ensure-ds) user-id task-id category-type category-id)
          (events/record-unlink! req :task task-id category-type category-id
                                 (events/fetch-category-title category-type category-id))
          {:status 200 :body {:success true}}))))

(defn reorder-task-handler
  "POST /api/tasks/:id/reorder — move a task before/after another task in the
  manual-sort order. Body: {:target-task-id :position} where position is
  \"before\" or \"after\". Computes a new :sort_order between the target and
  its neighbor (or +/- 1.0 at the edges) and returns {:success true
  :sort_order new-order}."
  [req]
  (let [user-id (common/get-user-id req)
        task-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [target-task-id position]} (:body req)
        all-tasks (db.task/list-tasks (common/ensure-ds) user-id :manual)
        target-idx (->> all-tasks
                        (map-indexed vector)
                        (some (fn [[idx task]] (when (= (:id task) target-task-id) idx))))
        target-order (:sort_order (nth all-tasks target-idx))
        neighbor-idx (if (= position "before") (dec target-idx) (inc target-idx))
        neighbor-order (when (and (>= neighbor-idx 0) (< neighbor-idx (count all-tasks)))
                         (:sort_order (nth all-tasks neighbor-idx)))
        new-order (cond
                    (nil? neighbor-order)
                    (if (= position "before")
                      (- target-order 1.0)
                      (+ target-order 1.0))
                    :else
                    (/ (+ target-order neighbor-order) 2.0))]
    (db.task/reorder-task (common/ensure-ds) user-id task-id new-order)
    {:status 200 :body {:success true :sort_order new-order}}))

(defn set-due-date-handler
  "PUT /api/tasks/:id/due-date — set or clear a task's due date. Body:
  {:due-date} (nullable ISO date string). Returns the updated task on 200
  and logs an :update event diffing :due_date/:due_time/:today/:lined_up_for/
  :urgency, since the DB layer may cascade related fields."
  [req]
  (let [user-id (common/get-user-id req)
        task-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [due-date]} (:body req)
        before (events/fetch-fields :tasks task-id [:due_date :due_time :today :lined_up_for :urgency])
        result (db.task/set-task-due-date (common/ensure-ds) user-id task-id due-date)]
    (events/record-update! req :task task-id before
                           (select-keys result [:due_date :due_time :today :lined_up_for :urgency]))
    {:status 200 :body result}))

(defn set-due-time-handler
  "PUT /api/tasks/:id/due-time — set or clear a task's due time. Body:
  {:due-time} as HH:MM 24-hour, or nil/empty to clear. 400 on invalid
  format. Returns the updated task on 200 and logs an :update event for
  :due_time."
  [req]
  (let [user-id (common/get-user-id req)
        task-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [due-time]} (:body req)]
    (if (common/valid-time-format? due-time)
      (let [before (events/fetch-fields :tasks task-id [:due_time])
            result (db.task/set-task-due-time (common/ensure-ds) user-id task-id due-time)]
        (events/record-update! req :task task-id before (select-keys result [:due_time]))
        {:status 200 :body result})
      {:status 400 :body {:error "Invalid time format. Use HH:MM (24-hour format)"}})))

(defn set-task-done-handler
  "PUT /api/tasks/:id/done — mark a task done or undone. Body: {:done} as a
  boolean (required; 400 if absent). Returns the updated task on 200, 404
  if not found. Logs an :update event diffing :done/:done_at/:today/
  :lined_up_for, since toggling done can clear scheduling fields."
  [req]
  (if-not (contains? (:body req) :done)
    {:status 400 :body {:error "Missing required field: done"}}
    (let [user-id (common/get-user-id req)
          task-id (Integer/parseInt (get-in req [:params :id]))
          done? (boolean (get-in req [:body :done]))
          before (events/fetch-fields :tasks task-id [:done :done_at :today :lined_up_for])
          result (db.task/set-task-done (common/ensure-ds) user-id task-id done?)]
      (if result
        (do (events/record-update! req :task task-id before
                                   (select-keys result [:done :done_at :today :lined_up_for]))
            {:status 200 :body result})
        {:status 404 :body {:error "Task not found"}}))))

(defn- make-task-property-handler [field valid-values error-message]
  (fn [req]
    (let [value (get-in req [:body field])]
      (if-not (contains? valid-values value)
        {:status 400 :body {:error error-message}}
        (let [user-id (common/get-user-id req)
              task-id (Integer/parseInt (get-in req [:params :id]))
              before (events/fetch-fields :tasks task-id [field])
              result (db.task/set-task-field (common/ensure-ds) user-id task-id field value)]
          (if result
            (do (events/record-update! req :task task-id before (select-keys result [field]))
                {:status 200 :body result})
            {:status 404 :body {:error "task not found"}}))))))

(def set-task-scope-handler
  "PUT /api/tasks/:id/scope — set a task's scope. Body: {:scope} which must be
  one of `private`, `both`, or `work`. 400 on invalid value, 404 if the task
  is not found, 200 with the updated row otherwise. Logs an :update event
  for :scope."
  (make-task-property-handler :scope db/valid-scopes
                              "Invalid scope. Must be 'private', 'both', or 'work'"))

(def set-task-importance-handler
  "PUT /api/tasks/:id/importance — set a task's importance. Body:
  {:importance} which must be one of `normal`, `important`, or `critical`.
  400 on invalid value, 404 if the task is not found, 200 with the updated
  row otherwise. Logs an :update event for :importance."
  (make-task-property-handler :importance db/valid-importances
                              "Invalid importance. Must be 'normal', 'important', or 'critical'"))

(def set-task-urgency-handler
  "PUT /api/tasks/:id/urgency — set a task's urgency. Body: {:urgency} which
  must be one of `default`, `urgent`, or `superurgent`. 400 on invalid
  value, 404 if the task is not found, 200 with the updated row otherwise.
  Logs an :update event for :urgency."
  (make-task-property-handler :urgency db/valid-urgencies
                              "Invalid urgency. Must be 'default', 'urgent', or 'superurgent'"))

(defn set-task-today-handler
  "PUT /api/tasks/:id/today — toggle whether a task is on today's list. Body:
  {:today} as a boolean (required; 400 if absent). Returns the updated task
  on 200, 404 if not found. Logs an :update event diffing :today/
  :lined_up_for, since adding to today may clear a queued lined-up date."
  [req]
  (if-not (contains? (:body req) :today)
    {:status 400 :body {:error "Missing required field: today"}}
    (let [user-id (common/get-user-id req)
          task-id (Integer/parseInt (get-in req [:params :id]))
          today? (boolean (get-in req [:body :today]))
          before (events/fetch-fields :tasks task-id [:today :lined_up_for])
          result (db.task/set-task-today (common/ensure-ds) user-id task-id today?)]
      (if result
        (do (events/record-update! req :task task-id before
                                   (select-keys result [:today :lined_up_for]))
            {:status 200 :body result})
        {:status 404 :body {:error "Task not found"}}))))

(defn set-task-lined-up-for-handler
  "PUT /api/tasks/:id/lined-up-for — schedule a task for a future date or
  clear the queue date. Body: {:lined_up_for} (nullable ISO date). Returns
  the updated task on 200, 404 if not found. Logs an :update event diffing
  :lined_up_for/:today, since queuing for a date may remove it from today."
  [req]
  (let [user-id (common/get-user-id req)
        task-id (Integer/parseInt (get-in req [:params :id]))
        date (get-in req [:body :lined_up_for])
        before (events/fetch-fields :tasks task-id [:lined_up_for :today])
        result (db.task/set-task-lined-up-for (common/ensure-ds) user-id task-id date)]
    (if result
      (do (events/record-update! req :task task-id before
                                 (select-keys result [:lined_up_for :today]))
          {:status 200 :body result})
      {:status 404 :body {:error "Task not found"}})))

(defn set-task-done-at-handler
  "PUT /api/tasks/:id/done-at — back-date when an already-done task was
  completed. Body: {:done-date} as YYYY-MM-DD (required; 400 on missing or
  malformed value). 404 if the task is not found or is not currently done.
  Returns the updated task on 200 and logs an :update event for :done_at."
  [req]
  (let [user-id (common/get-user-id req)
        task-id (Integer/parseInt (get-in req [:params :id]))
        done-date (get-in req [:body :done-date])]
    (if (and (string? done-date) (re-matches #"\d{4}-\d{2}-\d{2}" done-date))
      (let [before (events/fetch-fields :tasks task-id [:done_at])
            result (db.task/set-task-done-at (common/ensure-ds) user-id task-id done-date)]
        (if result
          (do (events/record-update! req :task task-id before (select-keys result [:done_at]))
              {:status 200 :body result})
          {:status 404 :body {:error "Task not found or not done"}}))
      {:status 400 :body {:error "Invalid date"}})))

(defn set-reminder-handler
  "PUT /api/tasks/:id/reminder — set or clear a task's reminder date. Body:
  {:reminder-date} (nullable ISO date). Returns the updated task on 200,
  404 if not found. Logs an :update event for :reminder_date."
  [req]
  (let [user-id (common/get-user-id req)
        task-id (Integer/parseInt (get-in req [:params :id]))
        reminder-date (get-in req [:body :reminder-date])
        before (events/fetch-fields :tasks task-id [:reminder_date])
        result (db.task/set-task-reminder (common/ensure-ds) user-id task-id reminder-date)]
    (if result
      (do (events/record-update! req :task task-id before (select-keys result [:reminder_date]))
          {:status 200 :body result})
      {:status 404 :body {:error "Task not found"}})))

(defn acknowledge-reminder-handler
  "PUT /api/tasks/:id/acknowledge-reminder — mark the task's currently due
  reminder as seen. Takes no body. Returns the updated task on 200, 404 if
  not found. Logs an :update event diffing :reminder/:reminder_date."
  [req]
  (let [user-id (common/get-user-id req)
        task-id (Integer/parseInt (get-in req [:params :id]))
        before (events/fetch-fields :tasks task-id [:reminder :reminder_date])
        result (db.task/acknowledge-task-reminder (common/ensure-ds) user-id task-id)]
    (if result
      (do (events/record-update! req :task task-id before
                                 (select-keys result [:reminder :reminder_date]))
          {:status 200 :body result})
      {:status 404 :body {:error "Task not found"}})))

(defn delete-task-handler
  "DELETE /api/tasks/:id — delete a task owned by the current user. Returns
  {:success true} on 200, or {:success false :error \"Task not found\"} with
  404. Snapshots the row before deletion and logs a :delete event with the
  full pre-deletion payload."
  [req]
  (let [user-id (common/get-user-id req)
        task-id (Integer/parseInt (get-in req [:params :id]))
        snapshot (events/fetch-row :tasks task-id)
        result (db.task/delete-task (common/ensure-ds) user-id task-id)]
    (if (:success result)
      (do (events/record-delete! req :task task-id snapshot)
          {:status 200 :body {:success true}})
      {:status 404 :body {:success false :error "Task not found"}})))
