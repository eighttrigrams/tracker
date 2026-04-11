(ns et.tr.server.journal-handler
  (:require [et.tr.server.common :as common]
            [et.tr.db :as db]
            [et.tr.db.journal :as db.journal]
            [clojure.string :as str]))

(defn get-journal-handler [req]
  (let [user-id (common/get-user-id req)
        journal-id (Integer/parseInt (get-in req [:params :id]))]
    (if-let [journal (db.journal/get-journal (common/ensure-ds) user-id journal-id)]
      {:status 200 :body journal}
      {:status 404 :body {:error "Journal not found"}})))

(defn list-journals-handler [req]
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
    {:status 200 :body (db.journal/list-journals (common/ensure-ds) user-id {:search-term search-term :context context :strict strict :categories categories})}))

(defn add-journal-handler [req]
  (let [user-id (common/get-user-id req)
        {:keys [title scope schedule-type]} (:body req)]
    (if (str/blank? title)
      {:status 400 :body {:success false :error "Title is required"}}
      {:status 201 :body (db.journal/add-journal (common/ensure-ds) user-id title (or scope "both") (or schedule-type "daily"))})))

(defn update-journal-handler [req]
  (let [user-id (common/get-user-id req)
        journal-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [title description tags]} (:body req)]
    (if (str/blank? title)
      {:status 400 :body {:success false :error "Title is required"}}
      {:status 200 :body (db.journal/update-journal (common/ensure-ds) user-id journal-id {:title title :description (or description "") :tags (or tags "")})})))

(defn delete-journal-handler [req]
  (let [user-id (common/get-user-id req)
        journal-id (Integer/parseInt (get-in req [:params :id]))
        result (db.journal/delete-journal (common/ensure-ds) user-id journal-id)]
    (if (:success result)
      {:status 200 :body {:success true}}
      {:status 404 :body {:success false :error "Journal not found"}})))

(def categorize-journal-handler (common/make-categorize-handler db.journal/categorize-journal))
(def uncategorize-journal-handler (common/make-uncategorize-handler db.journal/uncategorize-journal))

(def set-journal-scope-handler
  (common/make-entity-property-handler :scope db/valid-scopes
                                       "Invalid scope. Must be 'private', 'both', or 'work'"
                                       {:entity-type :journal :set-fn db.journal/set-journal-field}))

(defn set-journal-schedule-type-handler [req]
  (let [user-id (common/get-user-id req)
        journal-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [schedule-type]} (:body req)]
    (if-not (#{"daily" "weekly"} schedule-type)
      {:status 400 :body {:error "Invalid schedule type. Must be 'daily' or 'weekly'"}}
      (if-let [result (db.journal/set-journal-schedule-type (common/ensure-ds) user-id journal-id schedule-type)]
        {:status 200 :body result}
        {:status 404 :body {:error "Journal not found"}}))))

(defn create-entry-handler [req]
  (let [user-id (common/get-user-id req)
        journal-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [date]} (:body req)]
    (if (not (common/valid-date-format? date))
      {:status 400 :body {:error "Invalid date format. Use YYYY-MM-DD"}}
      (if-let [entry (db.journal/create-entry-for-journal (common/ensure-ds) user-id journal-id date)]
        {:status 201 :body entry}
        {:status 404 :body {:error "Journal not found"}}))))
