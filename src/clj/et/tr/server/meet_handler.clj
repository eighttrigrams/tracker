(ns et.tr.server.meet-handler
  (:require [et.tr.server.common :as common]
            [et.tr.server.events :as events]
            [et.tr.db :as db]
            [et.tr.db.meet :as db.meet]
            [clojure.string :as str]))

(defn get-meet-handler [req]
  (let [user-id (common/get-user-id req)
        meet-id (Integer/parseInt (get-in req [:params :id]))]
    (if-let [meet (db.meet/get-meet (common/ensure-ds) user-id meet-id)]
      {:status 200 :body meet}
      {:status 404 :body {:error "Meet not found"}})))

(defn list-meets-handler [req]
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
        categories (when (or people places projects goals)
                     {:people people :places places :projects projects :goals goals})]
    {:status 200 :body (db.meet/list-meets (common/ensure-ds) user-id {:search-term search-term :importance importance :context context :strict strict :categories categories :sort-mode sort-mode :excluded-places excluded-places :excluded-projects excluded-projects :series-id series-id})}))

(defn add-meet-handler [req]
  (let [user-id (common/get-user-id req)
        {:keys [title scope]} (:body req)]
    (if (str/blank? title)
      {:status 400 :body {:success false :error "Title is required"}}
      (let [meet (db.meet/add-meet (common/ensure-ds) user-id title (or scope "both"))]
        (events/record-create! req :meet (:id meet) meet)
        {:status 201 :body meet}))))

(defn update-meet-handler [req]
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

(defn delete-meet-handler [req]
  (let [user-id (common/get-user-id req)
        meet-id (Integer/parseInt (get-in req [:params :id]))
        snapshot (events/fetch-row :meets meet-id)
        result (db.meet/delete-meet (common/ensure-ds) user-id meet-id)]
    (if (:success result)
      (do (events/record-delete! req :meet meet-id snapshot)
          {:status 200 :body {:success true}})
      {:status 404 :body {:success false :error "Meet not found"}})))

(defn archive-meet-handler [req]
  (let [user-id (common/get-user-id req)
        meet-id (Integer/parseInt (get-in req [:params :id]))
        before (events/fetch-fields :meets meet-id [:archived])]
    (if-let [result (db.meet/archive-meet (common/ensure-ds) user-id meet-id)]
      (do (events/record-update! req :meet meet-id before
                                 (select-keys result [:archived]))
          {:status 200 :body result})
      {:status 404 :body {:error "Meet not found"}})))

(def categorize-meet-handler (common/make-categorize-handler db.meet/categorize-meet :meet))
(def uncategorize-meet-handler (common/make-uncategorize-handler db.meet/uncategorize-meet :meet))

(def set-meet-scope-handler
  (common/make-entity-property-handler :scope db/valid-scopes
                                       "Invalid scope. Must be 'private', 'both', or 'work'"
                                       {:entity-type :meet :set-fn db.meet/set-meet-field
                                        :table :meets}))

(def set-meet-importance-handler
  (common/make-entity-property-handler :importance db/valid-importances
                                       "Invalid importance. Must be 'normal', 'important', or 'critical'"
                                       {:entity-type :meet :set-fn db.meet/set-meet-field
                                        :table :meets}))

(defn set-meet-start-date-handler [req]
  (let [user-id (common/get-user-id req)
        meet-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [start-date]} (:body req)]
    (if (common/valid-date-format? start-date)
      (let [before (events/fetch-fields :meets meet-id [:start_date])
            result (db.meet/set-meet-start-date (common/ensure-ds) user-id meet-id start-date)]
        (events/record-update! req :meet meet-id before (select-keys result [:start_date]))
        {:status 200 :body result})
      {:status 400 :body {:error "Invalid date format. Use YYYY-MM-DD"}})))

(defn set-meet-start-time-handler [req]
  (let [user-id (common/get-user-id req)
        meet-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [start-time]} (:body req)]
    (if (common/valid-time-format? start-time)
      (let [before (events/fetch-fields :meets meet-id [:start_time])
            result (db.meet/set-meet-start-time (common/ensure-ds) user-id meet-id start-time)]
        (events/record-update! req :meet meet-id before (select-keys result [:start_time]))
        {:status 200 :body result})
      {:status 400 :body {:error "Invalid time format. Use HH:MM (24-hour format)"}})))
