(ns et.tr.server.journal-entry-handler
  (:require [et.tr.server.common :as common]
            [et.tr.db :as db]
            [et.tr.db.journal-entry :as db.journal-entry]
            [clojure.string :as str]))

(defn get-journal-entry-handler [req]
  (let [user-id (common/get-user-id req)
        entry-id (Integer/parseInt (get-in req [:params :id]))]
    (if-let [entry (db.journal-entry/get-journal-entry (common/ensure-ds) user-id entry-id)]
      {:status 200 :body entry}
      {:status 404 :body {:error "Journal entry not found"}})))

(defn list-journal-entries-handler [req]
  (let [user-id (common/get-user-id req)
        search-term (get-in req [:params "q"])
        importance (get-in req [:params "importance"])
        context (get-in req [:params "context"])
        strict (= "true" (get-in req [:params "strict"]))
        people (common/parse-category-param (get-in req [:params "people"]))
        places (common/parse-category-param (get-in req [:params "places"]))
        projects (common/parse-category-param (get-in req [:params "projects"]))
        goals (common/parse-category-param (get-in req [:params "goals"]))
        sort-mode (get-in req [:params "sortMode"])
        journal-id (when-let [jid (get-in req [:params "journalId"])]
                     (try (Integer/parseInt jid) (catch Exception _ nil)))
        categories (when (or people places projects goals)
                     {:people people :places places :projects projects :goals goals})]
    {:status 200 :body (db.journal-entry/list-journal-entries (common/ensure-ds) user-id {:search-term search-term :importance importance :context context :strict strict :categories categories :sort-mode sort-mode :journal-id journal-id})}))

(defn list-today-journal-entries-handler [req]
  (let [user-id (common/get-user-id req)
        context (get-in req [:params "context"])
        strict (= "true" (get-in req [:params "strict"]))]
    {:status 200 :body (db.journal-entry/list-today-journal-entries (common/ensure-ds) user-id {:context context :strict strict})}))

(defn add-journal-entry-handler [req]
  (let [user-id (common/get-user-id req)
        {:keys [title scope]} (:body req)]
    (if (str/blank? title)
      {:status 400 :body {:success false :error "Title is required"}}
      {:status 201 :body (db.journal-entry/add-journal-entry (common/ensure-ds) user-id title (or scope "both"))})))

(defn update-journal-entry-handler [req]
  (let [user-id (common/get-user-id req)
        entry-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [title description tags]} (:body req)]
    (if (str/blank? title)
      {:status 400 :body {:success false :error "Title is required"}}
      {:status 200 :body (db.journal-entry/update-journal-entry (common/ensure-ds) user-id entry-id {:title title :description (or description "") :tags (or tags "")})})))

(defn delete-journal-entry-handler [req]
  (let [user-id (common/get-user-id req)
        entry-id (Integer/parseInt (get-in req [:params :id]))
        result (db.journal-entry/delete-journal-entry (common/ensure-ds) user-id entry-id)]
    (if (:success result)
      {:status 200 :body {:success true}}
      {:status 404 :body {:success false :error "Journal entry not found"}})))

(def categorize-journal-entry-handler (common/make-categorize-handler db.journal-entry/categorize-journal-entry))
(def uncategorize-journal-entry-handler (common/make-uncategorize-handler db.journal-entry/uncategorize-journal-entry))

(defn reorder-journal-entry-handler [req]
  (let [user-id (common/get-user-id req)
        entry-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [target-entry-id position]} (:body req)
        all-entries (db.journal-entry/list-journal-entries (common/ensure-ds) user-id {:sort-mode "manual"})
        target-idx (->> all-entries
                        (map-indexed vector)
                        (some (fn [[idx e]] (when (= (:id e) target-entry-id) idx))))
        target-order (:sort_order (nth all-entries target-idx))
        neighbor-idx (if (= position "before") (dec target-idx) (inc target-idx))
        neighbor-order (when (and (>= neighbor-idx 0) (< neighbor-idx (count all-entries)))
                         (:sort_order (nth all-entries neighbor-idx)))
        new-order (cond
                    (nil? neighbor-order)
                    (if (= position "before")
                      (- target-order 1.0)
                      (+ target-order 1.0))
                    :else
                    (/ (+ target-order neighbor-order) 2.0))]
    (db.journal-entry/reorder-journal-entry (common/ensure-ds) user-id entry-id new-order)
    {:status 200 :body {:success true :sort_order new-order}}))

(def set-journal-entry-scope-handler
  (common/make-entity-property-handler :scope db/valid-scopes
                                       "Invalid scope. Must be 'private', 'both', or 'work'"
                                       {:entity-type :journal-entry :set-fn db.journal-entry/set-journal-entry-field}))

(def set-journal-entry-importance-handler
  (common/make-entity-property-handler :importance db/valid-importances
                                       "Invalid importance. Must be 'normal', 'important', or 'critical'"
                                       {:entity-type :journal-entry :set-fn db.journal-entry/set-journal-entry-field}))
