(ns et.tr.server.resource-handler
  (:require [et.tr.server.common :as common]
            [et.tr.db :as db]
            [et.tr.db.resource :as db.resource]
            [clojure.string :as str]))

(defn get-resource-handler [req]
  (let [user-id (common/get-user-id req)
        resource-id (Integer/parseInt (get-in req [:params :id]))]
    (if-let [resource (db.resource/get-resource (common/ensure-ds) user-id resource-id)]
      {:status 200 :body resource}
      {:status 404 :body {:error "Resource not found"}})))

(defn list-resources-handler [req]
  (let [user-id (common/get-user-id req)
        search-term (get-in req [:params "q"])
        importance (get-in req [:params "importance"])
        context (get-in req [:params "context"])
        strict (= "true" (get-in req [:params "strict"]))
        people (common/parse-category-param (get-in req [:params "people"]))
        places (common/parse-category-param (get-in req [:params "places"]))
        projects (common/parse-category-param (get-in req [:params "projects"]))
        goals (common/parse-category-param (get-in req [:params "goals"]))
        categories (when (or people places projects goals)
                     {:people people :places places :projects projects :goals goals})]
    {:status 200 :body (db.resource/list-resources (common/ensure-ds) user-id {:search-term search-term :importance importance :context context :strict strict :categories categories})}))

(defn add-resource-handler [req]
  (let [user-id (common/get-user-id req)
        {:keys [title link scope]} (:body req)]
    (cond
      (str/blank? title)
      {:status 400 :body {:success false :error "Title is required"}}

      (str/blank? link)
      {:status 400 :body {:success false :error "Link is required"}}

      (not (common/valid-url? link))
      {:status 400 :body {:success false :error "Invalid URL. Must start with http:// or https://"}}

      :else
      (let [resource (db.resource/add-resource (common/ensure-ds) user-id title link (or scope "both"))]
        {:status 201 :body resource}))))

(defn update-resource-handler [req]
  (let [user-id (common/get-user-id req)
        resource-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [title link description tags]} (:body req)]
    (cond
      (str/blank? title)
      {:status 400 :body {:success false :error "Title is required"}}

      (str/blank? link)
      {:status 400 :body {:success false :error "Link is required"}}

      (not (common/valid-url? link))
      {:status 400 :body {:success false :error "Invalid URL. Must start with http:// or https://"}}

      :else
      (let [resource (db.resource/update-resource (common/ensure-ds) user-id resource-id {:title title :link link :description (or description "") :tags (or tags "")})]
        {:status 200 :body resource}))))

(defn delete-resource-handler [req]
  (let [user-id (common/get-user-id req)
        resource-id (Integer/parseInt (get-in req [:params :id]))
        result (db.resource/delete-resource (common/ensure-ds) user-id resource-id)]
    (if (:success result)
      {:status 200 :body {:success true}}
      {:status 404 :body {:success false :error "Resource not found"}})))

(def categorize-resource-handler (common/make-categorize-handler db.resource/categorize-resource))
(def uncategorize-resource-handler (common/make-uncategorize-handler db.resource/uncategorize-resource))

(def set-resource-scope-handler
  (common/make-entity-property-handler :scope db/valid-scopes
                                       "Invalid scope. Must be 'private', 'both', or 'work'"
                                       {:entity-type :resource :set-fn db.resource/set-resource-field}))

(def set-resource-importance-handler
  (common/make-entity-property-handler :importance db/valid-importances
                                       "Invalid importance. Must be 'normal', 'important', or 'critical'"
                                       {:entity-type :resource :set-fn db.resource/set-resource-field}))
