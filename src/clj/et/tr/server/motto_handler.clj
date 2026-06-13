(ns et.tr.server.motto-handler
  (:require [et.tr.server.common :as common]
            [et.tr.server.events :as events]
            [et.tr.db :as db]
            [et.tr.db.motto :as db.motto]
            [clojure.string :as str]))

(defn get-motto-handler
  "GET /api/mottos/:id — fetch a single motto owned by the caller. Returns
  200 with the row, or 404 {:error} when not found or not owned."
  [req]
  (let [user-id (common/get-user-id req)
        motto-id (Integer/parseInt (get-in req [:params :id]))]
    (if-let [motto (db.motto/get-motto (common/ensure-ds) user-id motto-id)]
      {:status 200 :body motto}
      {:status 404 :body {:error "Motto not found"}})))

(defn list-mottos-handler
  "GET /api/mottos — list the caller's mottos. Query params: q (search
  term), context (scope), strict (\"true\" toggles strict match). Always
  returns 200 with a vector of rows."
  [req]
  (let [user-id (common/get-user-id req)
        search-term (get-in req [:params "q"])
        context (get-in req [:params "context"])
        strict (= "true" (get-in req [:params "strict"]))]
    {:status 200 :body (db.motto/list-mottos (common/ensure-ds) user-id
                         {:search-term search-term
                          :context context
                          :strict strict})}))

(defn add-motto-handler
  "POST /api/mottos — create a motto. Body: :title (required, non-blank),
  :description (optional, defaults to \"\"), :scope (defaults to \"both\").
  Returns 201 with the row, or 400 on validation failure. Records a
  :create event."
  [req]
  (let [user-id (common/get-user-id req)
        {:keys [title description scope]} (:body req)]
    (if (str/blank? title)
      {:status 400 :body {:success false :error "Title is required"}}
      (let [motto (db.motto/add-motto (common/ensure-ds) user-id title
                                      (or description "")
                                      (or scope "both"))]
        (events/record-create! req :motto (:id motto) motto)
        {:status 201 :body motto}))))

(defn update-motto-handler
  "PUT /api/mottos/:id — update mutable fields. Body: :title (required,
  non-blank), :description (optional). Returns 200 with the updated row,
  or 400/404 on failure. Records an :update event."
  [req]
  (let [user-id (common/get-user-id req)
        motto-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [title description]} (:body req)]
    (cond
      (str/blank? title)
      {:status 400 :body {:success false :error "Title is required"}}

      :else
      (let [before (events/fetch-fields :mottos motto-id [:title :description])
            motto (db.motto/update-motto (common/ensure-ds) user-id motto-id
                                         {:title title
                                          :description (or description "")})]
        (if motto
          (do (events/record-update! req :motto motto-id before
                                     (select-keys motto [:title :description]))
              {:status 200 :body motto})
          {:status 404 :body {:error "Motto not found"}})))))

(defn delete-motto-handler
  "DELETE /api/mottos/:id — delete a motto. Returns 200 {:success true}
  or 404 when not found/not owned. Records a :delete event with the row
  snapshot."
  [req]
  (let [user-id (common/get-user-id req)
        motto-id (Integer/parseInt (get-in req [:params :id]))
        snapshot (events/fetch-row :mottos motto-id)
        result (db.motto/delete-motto (common/ensure-ds) user-id motto-id)]
    (if (:success result)
      (do (events/record-delete! req :motto motto-id snapshot)
          {:status 200 :body {:success true}})
      {:status 404 :body {:success false :error "Motto not found"}})))

(def set-motto-scope-handler
  "PUT /api/mottos/:id/scope — set the motto's :scope. Body :scope must
  be one of #{\"private\" \"both\" \"work\"}. Records an :update event."
  (common/make-entity-property-handler :scope db/valid-scopes
                                       "Invalid scope. Must be 'private', 'both', or 'work'"
                                       {:entity-type :motto
                                        :set-fn db.motto/set-motto-field
                                        :table :mottos}))

(def set-motto-time-window-handler
  "PUT /api/mottos/:id/time-window — set when the motto is eligible to
  appear in the screensaver. Body :time_window must be one of
  #{\"both\" \"daytime\" \"nighttime\"}. Daytime covers the 12 hours
  starting at 08:00 local-app time, nighttime the other 12; \"both\"
  means always eligible. Records an :update event."
  (common/make-entity-property-handler :time_window db/valid-time-windows
                                       "Invalid time_window. Must be 'both', 'daytime', or 'nighttime'"
                                       {:entity-type :motto
                                        :set-fn db.motto/set-motto-field
                                        :table :mottos}))
