(ns et.tr.server.task-handler
  (:require [et.tr.server.common :as common]
            [et.tr.db :as db]
            [et.tr.db.task :as db.task]
            [clojure.string :as str]))

(defn get-task-handler [req]
  (let [user-id (common/get-user-id req)
        task-id (Integer/parseInt (get-in req [:params :id]))]
    (if-let [task (db.task/get-task (common/ensure-ds) user-id task-id)]
      {:status 200 :body task}
      {:status 404 :body {:error "Task not found"}})))

(defn list-tasks-handler [req]
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
        categories (when (or people places projects goals)
                     {:people people :places places :projects projects :goals goals})]
    {:status 200 :body (db.task/list-tasks (common/ensure-ds) user-id sort-mode {:search-term search-term :importance importance :context context :strict strict :categories categories :excluded-places excluded-places :excluded-projects excluded-projects})}))

(defn add-task-handler [req]
  (let [user-id (common/get-user-id req)
        {:keys [title scope]} (:body req)]
    (if (str/blank? title)
      {:status 400 :body {:success false :error "Title is required"}}
      (let [task (db.task/add-task (common/ensure-ds) user-id title (or scope "both"))]
        {:status 201 :body (assoc task :people [] :places [] :projects [] :goals [])}))))

(defn update-task-handler [req]
  (let [user-id (common/get-user-id req)
        task-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [title description tags]} (:body req)]
    (if (str/blank? title)
      {:status 400 :body {:success false :error "Title is required"}}
      (let [task (db.task/update-task (common/ensure-ds) user-id task-id {:title title :description (or description "") :tags (or tags "")})]
        {:status 200 :body task}))))

(def categorize-task-handler (common/make-categorize-handler db.task/categorize-task))
(def uncategorize-task-handler (common/make-uncategorize-handler db.task/uncategorize-task))

(defn reorder-task-handler [req]
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

(defn set-due-date-handler [req]
  (let [user-id (common/get-user-id req)
        task-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [due-date]} (:body req)
        result (db.task/set-task-due-date (common/ensure-ds) user-id task-id due-date)]
    {:status 200 :body result}))

(defn set-due-time-handler [req]
  (let [user-id (common/get-user-id req)
        task-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [due-time]} (:body req)]
    (if (common/valid-time-format? due-time)
      (let [result (db.task/set-task-due-time (common/ensure-ds) user-id task-id due-time)]
        {:status 200 :body result})
      {:status 400 :body {:error "Invalid time format. Use HH:MM (24-hour format)"}})))

(defn set-task-done-handler [req]
  (if-not (contains? (:body req) :done)
    {:status 400 :body {:error "Missing required field: done"}}
    (let [user-id (common/get-user-id req)
          task-id (Integer/parseInt (get-in req [:params :id]))
          done? (boolean (get-in req [:body :done]))
          result (db.task/set-task-done (common/ensure-ds) user-id task-id done?)]
      (if result
        {:status 200 :body result}
        {:status 404 :body {:error "Task not found"}}))))

(def set-task-scope-handler
  (common/make-entity-property-handler :scope db/valid-scopes
                                       "Invalid scope. Must be 'private', 'both', or 'work'"
                                       {:entity-type :task :set-fn db.task/set-task-field}))

(def set-task-importance-handler
  (common/make-entity-property-handler :importance db/valid-importances
                                       "Invalid importance. Must be 'normal', 'important', or 'critical'"
                                       {:entity-type :task :set-fn db.task/set-task-field}))

(def set-task-urgency-handler
  (common/make-entity-property-handler :urgency db/valid-urgencies
                                       "Invalid urgency. Must be 'default', 'urgent', or 'superurgent'"
                                       {:entity-type :task :set-fn db.task/set-task-field}))

(defn set-task-today-handler [req]
  (if-not (contains? (:body req) :today)
    {:status 400 :body {:error "Missing required field: today"}}
    (let [user-id (common/get-user-id req)
          task-id (Integer/parseInt (get-in req [:params :id]))
          today? (boolean (get-in req [:body :today]))
          result (db.task/set-task-today (common/ensure-ds) user-id task-id today?)]
      (if result
        {:status 200 :body result}
        {:status 404 :body {:error "Task not found"}}))))

(defn delete-task-handler [req]
  (let [user-id (common/get-user-id req)
        task-id (Integer/parseInt (get-in req [:params :id]))
        result (db.task/delete-task (common/ensure-ds) user-id task-id)]
    (if (:success result)
      {:status 200 :body {:success true}}
      {:status 404 :body {:success false :error "Task not found"}})))
