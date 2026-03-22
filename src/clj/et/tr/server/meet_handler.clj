(ns et.tr.server.meet-handler
  (:require [et.tr.server.common :as common]
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
        sort-mode (if (= "past" (get-in req [:params "sort"])) :past :upcoming)
        people (common/parse-category-param (get-in req [:params "people"]))
        places (common/parse-category-param (get-in req [:params "places"]))
        projects (common/parse-category-param (get-in req [:params "projects"]))
        goals (common/parse-category-param (get-in req [:params "goals"]))
        excluded-places (common/parse-category-param (get-in req [:params "excluded-places"]))
        excluded-projects (common/parse-category-param (get-in req [:params "excluded-projects"]))
        categories (when (or people places projects goals)
                     {:people people :places places :projects projects :goals goals})]
    {:status 200 :body (db.meet/list-meets (common/ensure-ds) user-id {:search-term search-term :importance importance :context context :strict strict :categories categories :sort-mode sort-mode :excluded-places excluded-places :excluded-projects excluded-projects})}))

(defn add-meet-handler [req]
  (let [user-id (common/get-user-id req)
        {:keys [title scope]} (:body req)]
    (if (str/blank? title)
      {:status 400 :body {:success false :error "Title is required"}}
      {:status 201 :body (db.meet/add-meet (common/ensure-ds) user-id title (or scope "both"))})))

(defn update-meet-handler [req]
  (let [user-id (common/get-user-id req)
        meet-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [title description tags]} (:body req)]
    (if (str/blank? title)
      {:status 400 :body {:success false :error "Title is required"}}
      {:status 200 :body (db.meet/update-meet (common/ensure-ds) user-id meet-id {:title title :description (or description "") :tags (or tags "")})})))

(defn delete-meet-handler [req]
  (let [user-id (common/get-user-id req)
        meet-id (Integer/parseInt (get-in req [:params :id]))
        result (db.meet/delete-meet (common/ensure-ds) user-id meet-id)]
    (if (:success result)
      {:status 200 :body {:success true}}
      {:status 404 :body {:success false :error "Meet not found"}})))

(def categorize-meet-handler (common/make-categorize-handler db.meet/categorize-meet))
(def uncategorize-meet-handler (common/make-uncategorize-handler db.meet/uncategorize-meet))

(def set-meet-scope-handler
  (common/make-entity-property-handler :scope db/valid-scopes
                                       "Invalid scope. Must be 'private', 'both', or 'work'"
                                       {:entity-type :meet :set-fn db.meet/set-meet-field}))

(def set-meet-importance-handler
  (common/make-entity-property-handler :importance db/valid-importances
                                       "Invalid importance. Must be 'normal', 'important', or 'critical'"
                                       {:entity-type :meet :set-fn db.meet/set-meet-field}))

(defn set-meet-start-date-handler [req]
  (let [user-id (common/get-user-id req)
        meet-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [start-date]} (:body req)]
    (if (common/valid-date-format? start-date)
      {:status 200 :body (db.meet/set-meet-start-date (common/ensure-ds) user-id meet-id start-date)}
      {:status 400 :body {:error "Invalid date format. Use YYYY-MM-DD"}})))

(defn set-meet-start-time-handler [req]
  (let [user-id (common/get-user-id req)
        meet-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [start-time]} (:body req)]
    (if (common/valid-time-format? start-time)
      {:status 200 :body (db.meet/set-meet-start-time (common/ensure-ds) user-id meet-id start-time)}
      {:status 400 :body {:error "Invalid time format. Use HH:MM (24-hour format)"}})))
