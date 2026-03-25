(ns et.tr.server.meeting-series-handler
  (:require [et.tr.server.common :as common]
            [et.tr.db :as db]
            [et.tr.db.meeting-series :as db.meeting-series]
            [clojure.string :as str]))

(defn get-meeting-series-handler [req]
  (let [user-id (common/get-user-id req)
        series-id (Integer/parseInt (get-in req [:params :id]))]
    (if-let [series (db.meeting-series/get-meeting-series (common/ensure-ds) user-id series-id)]
      {:status 200 :body series}
      {:status 404 :body {:error "Meeting series not found"}})))

(defn list-meeting-series-handler [req]
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

(defn add-meeting-series-handler [req]
  (let [user-id (common/get-user-id req)
        {:keys [title scope]} (:body req)]
    (if (str/blank? title)
      {:status 400 :body {:success false :error "Title is required"}}
      {:status 201 :body (db.meeting-series/add-meeting-series (common/ensure-ds) user-id title (or scope "both"))})))

(defn update-meeting-series-handler [req]
  (let [user-id (common/get-user-id req)
        series-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [title description tags]} (:body req)]
    (if (str/blank? title)
      {:status 400 :body {:success false :error "Title is required"}}
      {:status 200 :body (db.meeting-series/update-meeting-series (common/ensure-ds) user-id series-id {:title title :description (or description "") :tags (or tags "")})})))

(defn delete-meeting-series-handler [req]
  (let [user-id (common/get-user-id req)
        series-id (Integer/parseInt (get-in req [:params :id]))
        result (db.meeting-series/delete-meeting-series (common/ensure-ds) user-id series-id)]
    (if (:success result)
      {:status 200 :body {:success true}}
      {:status 404 :body {:success false :error "Meeting series not found"}})))

(defn create-next-meeting-handler [req]
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
        {:status 201 :body meet}
        {:status 404 :body {:error "Meeting series not found"}}))))

(def categorize-meeting-series-handler (common/make-categorize-handler db.meeting-series/categorize-meeting-series))
(def uncategorize-meeting-series-handler (common/make-uncategorize-handler db.meeting-series/uncategorize-meeting-series))

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

(defn set-meeting-series-schedule-handler [req]
  (let [user-id (common/get-user-id req)
        series-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [schedule-days schedule-time schedule-mode schedule-anchor]} (:body req)]
    (if (not (valid-schedule-time? schedule-time))
      {:status 400 :body {:error "Invalid time format"}}
      (if-let [result (db.meeting-series/set-meeting-series-schedule (common/ensure-ds) user-id series-id schedule-days schedule-time schedule-mode schedule-anchor)]
        {:status 200 :body result}
        {:status 404 :body {:error "Meeting series not found"}}))))

(defn auto-create-meetings-handler [req]
  (let [user-id (common/get-user-id req)
        created (db.meeting-series/auto-create-meetings (common/ensure-ds) user-id)]
    {:status 200 :body {:created created}}))

(def set-meeting-series-scope-handler
  (common/make-entity-property-handler :scope db/valid-scopes
                                       "Invalid scope. Must be 'private', 'both', or 'work'"
                                       {:entity-type :meeting-series :set-fn db.meeting-series/set-meeting-series-field}))
