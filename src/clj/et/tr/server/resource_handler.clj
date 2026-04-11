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
        domain (get-in req [:params "domain"])
        sort-mode (get-in req [:params "sortMode"])
        categories (when (or people places projects goals)
                     {:people people :places places :projects projects :goals goals})]
    {:status 200 :body (db.resource/list-resources (common/ensure-ds) user-id {:search-term search-term :importance importance :context context :strict strict :categories categories :domain domain :sort-mode sort-mode})}))

(defn add-resource-handler [req]
  (let [user-id (common/get-user-id req)
        {:keys [title link scope]} (:body req)]
    (cond
      (str/blank? title)
      {:status 400 :body {:success false :error "Title is required"}}

      (and (seq link) (not (common/valid-url? link)))
      {:status 400 :body {:success false :error "Invalid URL. Must start with http:// or https://"}}

      :else
      (let [effective-link (when (seq link) link)
            title (if (and effective-link (common/youtube-url? effective-link))
                    (or (common/fetch-youtube-title effective-link) title)
                    title)
            resource (db.resource/add-resource (common/ensure-ds) user-id title effective-link (or scope "both"))]
        {:status 201 :body resource}))))

(defn update-resource-handler [req]
  (let [user-id (common/get-user-id req)
        resource-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [title link description tags]} (:body req)]
    (cond
      (str/blank? title)
      {:status 400 :body {:success false :error "Title is required"}}

      (and (seq link) (not (common/valid-url? link)))
      {:status 400 :body {:success false :error "Invalid URL. Must start with http:// or https://"}}

      :else
      (let [effective-link (when (seq link) link)
            resource (db.resource/update-resource (common/ensure-ds) user-id resource-id {:title title :link effective-link :description (or description "") :tags (or tags "")})]
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

(defn reorder-resource-handler [req]
  (let [user-id (common/get-user-id req)
        resource-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [target-resource-id position]} (:body req)
        all-resources (db.resource/list-resources (common/ensure-ds) user-id {:sort-mode "manual"})
        target-idx (->> all-resources
                        (map-indexed vector)
                        (some (fn [[idx r]] (when (= (:id r) target-resource-id) idx))))
        target-order (:sort_order (nth all-resources target-idx))
        neighbor-idx (if (= position "before") (dec target-idx) (inc target-idx))
        neighbor-order (when (and (>= neighbor-idx 0) (< neighbor-idx (count all-resources)))
                         (:sort_order (nth all-resources neighbor-idx)))
        new-order (cond
                    (nil? neighbor-order)
                    (if (= position "before")
                      (- target-order 1.0)
                      (+ target-order 1.0))
                    :else
                    (/ (+ target-order neighbor-order) 2.0))]
    (db.resource/reorder-resource (common/ensure-ds) user-id resource-id new-order)
    {:status 200 :body {:success true :sort_order new-order}}))

(def set-resource-scope-handler
  (common/make-entity-property-handler :scope db/valid-scopes
                                       "Invalid scope. Must be 'private', 'both', or 'work'"
                                       {:entity-type :resource :set-fn db.resource/set-resource-field}))

(def set-resource-importance-handler
  (common/make-entity-property-handler :importance db/valid-importances
                                       "Invalid importance. Must be 'normal', 'important', or 'critical'"
                                       {:entity-type :resource :set-fn db.resource/set-resource-field}))
