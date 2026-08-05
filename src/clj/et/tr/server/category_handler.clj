(ns et.tr.server.category-handler
  (:require [et.tr.server.common :as common]
            [et.tr.server.events :as events]
            [et.tr.db :as db]
            [et.tr.ordering :as ordering]
            [et.tr.db.category :as db.category]
            [clojure.string :as str]))

(defn- list-opts [req]
  {:search-term (get-in req [:params "q"])
   :context (get-in req [:params "context"])
   :strict (= "true" (get-in req [:params "strict"]))})

(defn- list-handler [list-fn]
  (fn [req]
    {:status 200 :body (list-fn (common/ensure-ds) (common/get-user-id req) (list-opts req))}))

(defn- add-handler [add-fn entity-type label]
  (fn [req]
    (let [user-id (common/get-user-id req)
          {:keys [name]} (:body req)]
      (if (str/blank? name)
        {:status 400 :body {:success false :error "Name is required"}}
        (try
          (let [row (add-fn (common/ensure-ds) user-id name)]
            (events/record-create! req entity-type (:id row) row)
            {:status 201 :body row})
          (catch Exception _
            {:status 409 :body {:success false :error (str label " already exists")}}))))))

(def list-people-handler
  "GET /api/people?q=&context=&strict= — list the People group for the
  authenticated user, ordered by sort_order. Optional `q` filters across
  name/badge_title/tags; `context` (\"private\"/\"work\") plus `strict`
  restrict the rows to those matching the requested scope."
  (list-handler db.category/list-people))

(def list-places-handler
  "GET /api/places?q=&context=&strict= — list the Places group. Same options as
  GET /api/people."
  (list-handler db.category/list-places))

(def list-workstreams-handler
  "GET /api/workstreams?q=&context=&strict= — list the Workstreams group. Same
  options as GET /api/people."
  (list-handler db.category/list-workstreams))

(def list-projects-handler
  "GET /api/projects?q=&context=&strict= — list the Projects group. Same options
  as GET /api/people."
  (list-handler db.category/list-projects))

(def list-goals-handler
  "GET /api/goals?q=&context=&strict= — list the Goals group. Same options as
  GET /api/people."
  (list-handler db.category/list-goals))

(def list-assets-handler
  "GET /api/assets?q=&context=&strict= — list the Assets group. Same options as
  GET /api/people."
  (list-handler db.category/list-assets))

(def add-person-handler
  "POST /api/people — create a category in the People group. Body: {:name}.
  Rejects blank names with 400 and {:success false :error \"Name is
  required\"}. On unique-key collision returns 409. Names are unique per user
  across ALL groups now that they share one table, so this also rejects a name
  another group already uses. Records a :create event and returns 201 with the
  inserted row."
  (add-handler db.category/add-person :person "Person"))

(def add-place-handler
  "POST /api/places — create a category in the Places group. Body: {:name}. See
  POST /api/people for the error cases."
  (add-handler db.category/add-place :place "Place"))

(def add-workstream-handler
  "POST /api/workstreams — create a category in the Workstreams group. Body:
  {:name}. See POST /api/people for the error cases."
  (add-handler db.category/add-workstream :workstream "Workstream"))

(def add-project-handler
  "POST /api/projects — create a category in the Projects group. Body: {:name}.
  See POST /api/people for the error cases."
  (add-handler db.category/add-project :project "Project"))

(def add-goal-handler
  "POST /api/goals — create a category in the Goals group. Body: {:name}. See
  POST /api/people for the error cases."
  (add-handler db.category/add-goal :goal "Goal"))

(def add-asset-handler
  "POST /api/assets — create a category in the Assets group. Body: {:name}. See
  POST /api/people for the error cases."
  (add-handler db.category/add-asset :asset "Asset"))

(defn- update-category-handler*
  [req entity-type category-type label db-fn]
  (let [user-id (common/get-user-id req)
        cat-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [name description tags badge-title]} (:body req)
        expected (get-in req [:body :expected-modified-at])]
    (if (str/blank? name)
      {:status 400 :body {:success false :error "Name is required"}}
      (let [before (events/fetch-fields :categories cat-id [:name :description :tags :badge_title])
            result (try
                     (db-fn (common/ensure-ds) user-id cat-id name (or description "") (or tags "") badge-title expected)
                     (catch Exception _ ::name-collision))]
        (cond
          (= result ::name-collision)
          {:status 409 :body {:success false :error (str label " with this name already exists")}}

          result
          (do (events/record-update! req entity-type cat-id before
                                     (select-keys result [:name :description :tags :badge_title]))
              {:status 200 :body result})

          :else
          (common/conflict-or-not-found (db.category/get-category (common/ensure-ds) user-id cat-id category-type)
                                        (str label " not found")))))))

(defn update-person-handler
  "PUT /api/people/:id — update a category in the People group. Body: {:name
  :description :tags :badge-title}. Rejects blank :name with 400; returns 404
  if the row is missing or is not in this group, and 409 on a unique-name
  collision. On success snapshots :name/:description/:tags/:badge_title before
  the write and records an :update event."
  [req]
  (update-category-handler* req :person "person" "Person" db.category/update-person))

(defn update-place-handler
  "PUT /api/places/:id — update a category in the Places group. See
  PUT /api/people/:id."
  [req]
  (update-category-handler* req :place "place" "Place" db.category/update-place))

(defn update-workstream-handler
  "PUT /api/workstreams/:id — update a category in the Workstreams group. See
  PUT /api/people/:id."
  [req]
  (update-category-handler* req :workstream "workstream" "Workstream" db.category/update-workstream))

(defn update-project-handler
  "PUT /api/projects/:id — update a category in the Projects group. See
  PUT /api/people/:id."
  [req]
  (update-category-handler* req :project "project" "Project" db.category/update-project))

(defn update-goal-handler
  "PUT /api/goals/:id — update a category in the Goals group. See
  PUT /api/people/:id."
  [req]
  (update-category-handler* req :goal "goal" "Goal" db.category/update-goal))

(defn update-asset-handler
  "PUT /api/assets/:id — update a category in the Assets group. See
  PUT /api/people/:id."
  [req]
  (update-category-handler* req :asset "asset" "Asset" db.category/update-asset))

(defn- get-category-handler*
  [req category-type label]
  (let [user-id (common/get-user-id req)
        cat-id (Integer/parseInt (get-in req [:params :id]))]
    (if-let [row (db.category/get-category (common/ensure-ds) user-id cat-id category-type)]
      {:status 200 :body row}
      {:status 404 :body {:error (str label " not found")}})))

(defn get-person-handler
  "GET /api/people/:id — fetch a single category from the People group owned by
  the caller. Returns 200 with the row, or 404 when not found, not owned, or in
  another group."
  [req]
  (get-category-handler* req "person" "Person"))

(defn get-place-handler
  "GET /api/places/:id — fetch a single category from the Places group. See
  GET /api/people/:id."
  [req]
  (get-category-handler* req "place" "Place"))

(defn get-workstream-handler
  "GET /api/workstreams/:id — fetch a single category from the Workstreams
  group. See GET /api/people/:id."
  [req]
  (get-category-handler* req "workstream" "Workstream"))

(defn get-project-handler
  "GET /api/projects/:id — fetch a single category from the Projects group. See
  GET /api/people/:id."
  [req]
  (get-category-handler* req "project" "Project"))

(defn get-goal-handler
  "GET /api/goals/:id — fetch a single category from the Goals group. See
  GET /api/people/:id."
  [req]
  (get-category-handler* req "goal" "Goal"))

(defn get-asset-handler
  "GET /api/assets/:id — fetch a single category from the Assets group. See
  GET /api/people/:id."
  [req]
  (get-category-handler* req "asset" "Asset"))

(defn- scope-handler [entity-type set-fn]
  (common/make-entity-property-handler :scope db/valid-scopes
                                       "Invalid scope. Must be 'private', 'both', or 'work'"
                                       {:entity-type entity-type
                                        :set-fn set-fn
                                        :table :categories}))

(def set-person-scope-handler
  "PUT /api/people/:id/scope — set the category's :scope field. Body field
  :scope must be one of db/valid-scopes (\"private\", \"both\", or \"work\").
  Returns 200 with the updated row, 400 {:error} on an invalid value, or 404
  {:error} when the category does not exist, is not owned by the caller, or is
  not in this group."
  (scope-handler :person db.category/set-person-field))

(def set-place-scope-handler
  "PUT /api/places/:id/scope — set the category's :scope field. See
  PUT /api/people/:id/scope."
  (scope-handler :place db.category/set-place-field))

(def set-workstream-scope-handler
  "PUT /api/workstreams/:id/scope — set the category's :scope field. See
  PUT /api/people/:id/scope."
  (scope-handler :workstream db.category/set-workstream-field))

(def set-project-scope-handler
  "PUT /api/projects/:id/scope — set the category's :scope field. See
  PUT /api/people/:id/scope."
  (scope-handler :project db.category/set-project-field))

(def set-goal-scope-handler
  "PUT /api/goals/:id/scope — set the category's :scope field. See
  PUT /api/people/:id/scope."
  (scope-handler :goal db.category/set-goal-field))

(def set-asset-scope-handler
  "PUT /api/assets/:id/scope — set the category's :scope field. See
  PUT /api/people/:id/scope."
  (scope-handler :asset db.category/set-asset-field))

(def ^:private category-config
  "URL segment -> the Category Group it names."
  (into {} (map (fn [{:keys [type key]}] [(name key) {:type type}])) db/category-groups))

(defn delete-category-handler
  "DELETE /api/:category/:id — delete a category, where :category is one of
  \"people\", \"places\", \"workstreams\", \"projects\", \"goals\",
  \"assets\". Returns 400 {:success false :error \"Invalid category type\"} for
  any other :category, and 404 if the row is missing. On success snapshots the
  full row, records a :delete event under the appropriate entity keyword, and
  returns {:success true}."
  [req]
  (let [user-id (common/get-user-id req)
        category-id (Integer/parseInt (get-in req [:params :id]))
        category-key (get-in req [:params :category])
        {:keys [type]} (get category-config category-key)]
    (if-not type
      {:status 400 :body {:success false :error "Invalid category type"}}
      (let [snapshot (events/fetch-row :categories category-id)
            result (db.category/delete-category (common/ensure-ds) user-id category-id type)]
        (if (:success result)
          (do (events/record-delete! req (keyword type) category-id snapshot)
              {:status 200 :body {:success true}})
          {:status 404 :body {:success false :error (str (str/capitalize type) " not found")}})))))

(defn reorder-category-handler
  "POST /api/{people,places,workstreams,projects,goals,assets}/:id/reorder —
  each route supplies the matching `list-fn` (e.g. db.category/list-people).
  Body: {:target-category-id :position} where :position is \"before\" or
  \"after\". Computes a new fractional :sort_order between the target and its
  neighbor (or ±1.0 at the ends), persists it, and returns {:success true
  :sort_order}; 404 when the target is not in the list, and 404 when the
  subject :id is not in it either.

  All six groups share the single :categories ordering context, because they
  share categories.sort_order. `list-fn` keeps the new position computed among
  one group's rows — but the row it is written to is named by the URL, and
  before the unification a mismatch between the two was caught by the schema:
  the write was `UPDATE projects ... WHERE id = <a person>`, matching nothing.
  One table means that write now succeeds, reordering the subject's own group
  by a position computed in a group it does not belong to. Hence the explicit
  membership check: both ends of the request must name the same group."
  [req list-fn]
  (let [user-id (common/get-user-id req)
        category-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [target-category-id position]} (:body req)
        all-categories (list-fn (common/ensure-ds) user-id)
        in-group? (some #(= category-id (:id %)) all-categories)
        new-order (ordering/value-between :categories all-categories target-category-id position)]
    (cond
      (not in-group?)
      {:status 404 :body {:success false :error "Category not in this group"}}

      (nil? new-order)
      {:status 404 :body {:error "Target not found"}}

      :else
      {:status 200 :body (db.category/reorder-category (common/ensure-ds) user-id category-id new-order :categories)})))

(defn set-category-group-handler
  "PUT /api/categories/:id/group — move a category into another Group. Body:
  {:group} where :group is one of \"person\", \"place\", \"workstream\",
  \"project\", \"goal\", \"asset\".

  The item keeps its id and therefore its name, description, tags, badge title,
  scope and every association it had with tasks, issues, resources, meets,
  meeting series, recurring tasks, journals and journal entries; the
  category_type mirrored in those join tables and in the user's category rules
  is updated in the same transaction. Its sort_order becomes the last position
  in the destination group, since its old value was a position in a list it has
  left.

  Returns 200 with the updated row, 400 {:error} on an unknown group, and 404
  {:error} when the category does not exist or is not owned by the caller.
  Moving an item to the group it is already in is a no-op that still returns
  200."
  [req]
  (let [user-id (common/get-user-id req)
        category-id (Integer/parseInt (get-in req [:params :id]))
        new-group (get-in req [:body :group])]
    (if-not (contains? db/valid-category-types new-group)
      {:status 400 :body {:success false
                          :error (str "Invalid group. Must be one of: "
                                      (str/join ", " db/category-type-order))}}
      (let [before (events/fetch-fields :categories category-id [:category_type :sort_order])]
        (if-let [result (db.category/set-category-group (common/ensure-ds) user-id category-id new-group)]
          (do (events/record-update! req (keyword new-group) category-id before
                                     (select-keys result [:category_type :sort_order]))
              {:status 200 :body result})
          {:status 404 :body {:success false :error "Category not found"}})))))
