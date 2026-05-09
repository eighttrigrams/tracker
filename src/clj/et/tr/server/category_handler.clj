(ns et.tr.server.category-handler
  (:require [et.tr.server.common :as common]
            [et.tr.server.events :as events]
            [et.tr.db.category :as db.category]
            [clojure.string :as str]))

(defn list-people-handler
  "GET /api/people — list all people for the authenticated user, ordered by
  sort_order. Returns the rows as produced by db.category/list-people."
  [req]
  (let [user-id (common/get-user-id req)]
    {:status 200 :body (db.category/list-people (common/ensure-ds) user-id)}))

(defn add-person-handler
  "POST /api/people — create a new person. Body: {:name}. Rejects blank names
  with 400 and {:success false :error \"Name is required\"}. On unique-key
  collision returns 409 {:success false :error \"Person already exists\"}.
  Records a :create event for the :person entity on success and returns 201
  with the inserted row."
  [req]
  (let [user-id (common/get-user-id req)
        {:keys [name]} (:body req)]
    (if (str/blank? name)
      {:status 400 :body {:success false :error "Name is required"}}
      (try
        (let [row (db.category/add-person (common/ensure-ds) user-id name)]
          (events/record-create! req :person (:id row) row)
          {:status 201 :body row})
        (catch Exception _
          {:status 409 :body {:success false :error "Person already exists"}})))))

(defn list-places-handler
  "GET /api/places — list all places for the authenticated user, ordered by
  sort_order. Returns the rows as produced by db.category/list-places."
  [req]
  (let [user-id (common/get-user-id req)]
    {:status 200 :body (db.category/list-places (common/ensure-ds) user-id)}))

(defn add-place-handler
  "POST /api/places — create a new place. Body: {:name}. Rejects blank names
  with 400 and {:success false :error \"Name is required\"}. On unique-key
  collision returns 409 {:success false :error \"Place already exists\"}.
  Records a :create event for the :place entity on success and returns 201
  with the inserted row."
  [req]
  (let [user-id (common/get-user-id req)
        {:keys [name]} (:body req)]
    (if (str/blank? name)
      {:status 400 :body {:success false :error "Name is required"}}
      (try
        (let [row (db.category/add-place (common/ensure-ds) user-id name)]
          (events/record-create! req :place (:id row) row)
          {:status 201 :body row})
        (catch Exception _
          {:status 409 :body {:success false :error "Place already exists"}})))))

(defn list-projects-handler
  "GET /api/projects — list all projects for the authenticated user, ordered
  by sort_order. Returns the rows as produced by db.category/list-projects."
  [req]
  (let [user-id (common/get-user-id req)]
    {:status 200 :body (db.category/list-projects (common/ensure-ds) user-id)}))

(defn add-project-handler
  "POST /api/projects — create a new project. Body: {:name}. Rejects blank
  names with 400 and {:success false :error \"Name is required\"}. On
  unique-key collision returns 409 {:success false :error \"Project already
  exists\"}. Records a :create event for the :project entity on success and
  returns 201 with the inserted row."
  [req]
  (let [user-id (common/get-user-id req)
        {:keys [name]} (:body req)]
    (if (str/blank? name)
      {:status 400 :body {:success false :error "Name is required"}}
      (try
        (let [row (db.category/add-project (common/ensure-ds) user-id name)]
          (events/record-create! req :project (:id row) row)
          {:status 201 :body row})
        (catch Exception _
          {:status 409 :body {:success false :error "Project already exists"}})))))

(defn list-goals-handler
  "GET /api/goals — list all goals for the authenticated user, ordered by
  sort_order. Returns the rows as produced by db.category/list-goals."
  [req]
  (let [user-id (common/get-user-id req)]
    {:status 200 :body (db.category/list-goals (common/ensure-ds) user-id)}))

(defn add-goal-handler
  "POST /api/goals — create a new goal. Body: {:name}. Rejects blank names
  with 400 and {:success false :error \"Name is required\"}. On unique-key
  collision returns 409 {:success false :error \"Goal already exists\"}.
  Records a :create event for the :goal entity on success and returns 201
  with the inserted row."
  [req]
  (let [user-id (common/get-user-id req)
        {:keys [name]} (:body req)]
    (if (str/blank? name)
      {:status 400 :body {:success false :error "Name is required"}}
      (try
        (let [row (db.category/add-goal (common/ensure-ds) user-id name)]
          (events/record-create! req :goal (:id row) row)
          {:status 201 :body row})
        (catch Exception _
          {:status 409 :body {:success false :error "Goal already exists"}})))))

(defn- update-category-handler*
  [req entity-type table label db-fn]
  (let [user-id (common/get-user-id req)
        cat-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [name description tags badge-title]} (:body req)]
    (if (str/blank? name)
      {:status 400 :body {:success false :error "Name is required"}}
      (try
        (let [before (events/fetch-fields table cat-id [:name :description :tags :badge_title])
              result (db-fn (common/ensure-ds) user-id cat-id name (or description "") (or tags "") badge-title)]
          (if result
            (do (events/record-update! req entity-type cat-id before
                                       (select-keys result [:name :description :tags :badge_title]))
                {:status 200 :body result})
            {:status 404 :body {:success false :error (str label " not found")}}))
        (catch Exception _
          {:status 409 :body {:success false :error (str label " with this name already exists")}})))))

(defn update-person-handler
  "PUT /api/people/:id — update the named person. Body: {:name :description
  :tags :badge-title}. Rejects blank :name with 400 {:success false :error
  \"Name is required\"}; returns 404 if the row is missing and 409 on a
  unique-name collision. On success snapshots :name/:description/:tags/
  :badge_title before the write and records an :update event for :person."
  [req]
  (update-category-handler* req :person :people "Person" db.category/update-person))

(defn update-place-handler
  "PUT /api/places/:id — update the named place. Body: {:name :description
  :tags :badge-title}. Rejects blank :name with 400 {:success false :error
  \"Name is required\"}; returns 404 if the row is missing and 409 on a
  unique-name collision. On success snapshots :name/:description/:tags/
  :badge_title before the write and records an :update event for :place."
  [req]
  (update-category-handler* req :place :places "Place" db.category/update-place))

(defn update-project-handler
  "PUT /api/projects/:id — update the named project. Body: {:name :description
  :tags :badge-title}. Rejects blank :name with 400 {:success false :error
  \"Name is required\"}; returns 404 if the row is missing and 409 on a
  unique-name collision. On success snapshots :name/:description/:tags/
  :badge_title before the write and records an :update event for :project."
  [req]
  (update-category-handler* req :project :projects "Project" db.category/update-project))

(defn update-goal-handler
  "PUT /api/goals/:id — update the named goal. Body: {:name :description
  :tags :badge-title}. Rejects blank :name with 400 {:success false :error
  \"Name is required\"}; returns 404 if the row is missing and 409 on a
  unique-name collision. On success snapshots :name/:description/:tags/
  :badge_title before the write and records an :update event for :goal."
  [req]
  (update-category-handler* req :goal :goals "Goal" db.category/update-goal))

(def ^:private category-config
  {"people" {:type "person" :table "people"}
   "places" {:type "place" :table "places"}
   "projects" {:type "project" :table "projects"}
   "goals" {:type "goal" :table "goals"}})

(defn delete-category-handler
  "DELETE /api/:category/:id — delete a category row, where :category is one
  of \"people\", \"places\", \"projects\", \"goals\". Returns 400 {:success
  false :error \"Invalid category type\"} for any other :category, and 404 if
  the row is missing. On success snapshots the full row, records a :delete
  event under the appropriate entity keyword, and returns {:success true}."
  [req]
  (let [user-id (common/get-user-id req)
        category-id (Integer/parseInt (get-in req [:params :id]))
        category-key (get-in req [:params :category])
        {:keys [type table]} (get category-config category-key)]
    (if-not type
      {:status 400 :body {:success false :error "Invalid category type"}}
      (let [snapshot (events/fetch-row (keyword table) category-id)
            result (db.category/delete-category (common/ensure-ds) user-id category-id type table)]
        (if (:success result)
          (do (events/record-delete! req (keyword type) category-id snapshot)
              {:status 200 :body {:success true}})
          {:status 404 :body {:success false :error (str (str/capitalize type) " not found")}})))))

(defn reorder-category-handler
  "POST /api/{people,places,projects,goals}/:id/reorder — each route supplies
  the matching `list-fn` (e.g. db.category/list-people) and
  `table-name` (\"people\"/\"places\"/\"projects\"/\"goals\"). Body:
  {:target-category-id :position} where :position is \"before\" or \"after\".
  Computes a new fractional :sort_order between the target and its neighbor
  (or ±1.0 at the ends), persists it, and returns {:success true :sort_order}."
  [req list-fn table-name]
  (let [user-id (common/get-user-id req)
        category-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [target-category-id position]} (:body req)
        all-categories (list-fn (common/ensure-ds) user-id)
        target-idx (->> all-categories
                        (map-indexed vector)
                        (some (fn [[idx cat]] (when (= (:id cat) target-category-id) idx))))
        target-order (:sort_order (nth all-categories target-idx))
        neighbor-idx (if (= position "before") (dec target-idx) (inc target-idx))
        neighbor-order (when (and (>= neighbor-idx 0) (< neighbor-idx (count all-categories)))
                         (:sort_order (nth all-categories neighbor-idx)))
        new-order (cond
                    (nil? neighbor-order)
                    (if (= position "before")
                      (- target-order 1.0)
                      (+ target-order 1.0))
                    :else
                    (/ (+ target-order neighbor-order) 2.0))]
    (db.category/reorder-category (common/ensure-ds) user-id category-id new-order table-name)
    {:status 200 :body {:success true :sort_order new-order}}))
