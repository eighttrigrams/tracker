(ns et.tr.server.recurring-task-handler
  (:require [et.tr.server.common :as common]
            [et.tr.db :as db]
            [et.tr.db.recurring-task :as db.recurring-task]
            [clojure.string :as str]))

(defn get-recurring-task-handler [req]
  (let [user-id (common/get-user-id req)
        rtask-id (Integer/parseInt (get-in req [:params :id]))]
    (if-let [rtask (db.recurring-task/get-recurring-task (common/ensure-ds) user-id rtask-id)]
      {:status 200 :body rtask}
      {:status 404 :body {:error "Recurring task not found"}})))

(defn list-recurring-tasks-handler [req]
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
    {:status 200 :body (db.recurring-task/list-recurring-tasks (common/ensure-ds) user-id {:search-term search-term :context context :strict strict :categories categories})}))

(defn add-recurring-task-handler [req]
  (let [user-id (common/get-user-id req)
        {:keys [title scope]} (:body req)]
    (if (str/blank? title)
      {:status 400 :body {:success false :error "Title is required"}}
      {:status 201 :body (db.recurring-task/add-recurring-task (common/ensure-ds) user-id title (or scope "both"))})))

(defn update-recurring-task-handler [req]
  (let [user-id (common/get-user-id req)
        rtask-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [title description tags]} (:body req)]
    (if (str/blank? title)
      {:status 400 :body {:success false :error "Title is required"}}
      {:status 200 :body (db.recurring-task/update-recurring-task (common/ensure-ds) user-id rtask-id {:title title :description (or description "") :tags (or tags "")})})))

(defn delete-recurring-task-handler [req]
  (let [user-id (common/get-user-id req)
        rtask-id (Integer/parseInt (get-in req [:params :id]))
        result (db.recurring-task/delete-recurring-task (common/ensure-ds) user-id rtask-id)]
    (if (:success result)
      {:status 200 :body {:success true}}
      {:status 404 :body {:success false :error "Recurring task not found"}})))

(defn create-next-task-handler [req]
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
        {:status 201 :body task}
        {:status 404 :body {:error "Recurring task not found"}}))))

(def categorize-recurring-task-handler (common/make-categorize-handler db.recurring-task/categorize-recurring-task))
(def uncategorize-recurring-task-handler (common/make-uncategorize-handler db.recurring-task/uncategorize-recurring-task))

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

(defn set-recurring-task-schedule-handler [req]
  (let [user-id (common/get-user-id req)
        rtask-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [schedule-days schedule-time schedule-mode biweekly-offset task-type]} (:body req)]
    (if (not (valid-schedule-time? schedule-time))
      {:status 400 :body {:error "Invalid time format"}}
      (if-let [result (db.recurring-task/set-recurring-task-schedule (common/ensure-ds) user-id rtask-id schedule-days schedule-time schedule-mode biweekly-offset task-type)]
        {:status 200 :body result}
        {:status 404 :body {:error "Recurring task not found"}}))))

(def set-recurring-task-scope-handler
  (common/make-entity-property-handler :scope db/valid-scopes
                                       "Invalid scope. Must be 'private', 'both', or 'work'"
                                       {:entity-type :recurring-task :set-fn db.recurring-task/set-recurring-task-field}))
