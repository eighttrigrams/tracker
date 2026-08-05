(ns et.tr.ui.state.categories
  "Client state for the Category Groups. Every group behaves identically — one
  table on the server, one shape here — so the per-group entry points are
  generated from et.tr.ui.constants/category-groups rather than written out six
  times."
  (:require [ajax.core :refer [GET]]
            [et.tr.filters :as filters]
            [et.tr.ui.api :as api]
            [et.tr.ui.constants :as constants]))

(defn- endpoint [group-key]
  (constants/category-key->endpoint group-key))

(defn- scope-query-string [app-state]
  (let [mode (:work-private-mode @app-state)
        strict? (:strict-mode @app-state)]
    (cond-> (str "?context=" (name mode))
      strict? (str "&strict=true"))))

(defn fetch-categories
  "Reload one group's collection into app-state under its plural key."
  [app-state auth-headers group-key]
  (GET (str (endpoint group-key) (scope-query-string app-state))
    {:response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler #(swap! app-state assoc group-key %)}))

(defn fetch-all-categories [app-state auth-headers]
  (doseq [group-key constants/category-key-order]
    (fetch-categories app-state auth-headers group-key)))

(defn set-category-scope [app-state auth-headers group-key id scope]
  (api/put-json (str (endpoint group-key) id "/scope")
    {:scope scope}
    (auth-headers)
    (fn [result]
      (let [mode (:work-private-mode @app-state)
            strict? (:strict-mode @app-state)]
        (swap! app-state update group-key
               (fn [coll]
                 (->> coll
                      (mapv #(if (= (:id %) id)
                               (assoc % :scope (:scope result) :modified_at (:modified_at result))
                               %))
                      (filterv #(filters/matches-scope? % mode strict?)))))))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update scope")))))

(defn- sort-by-modified [items]
  (->> items (sort-by :modified_at #(compare %2 %1)) vec))

(defn add-category [app-state auth-headers group-key name on-success]
  (api/post-json (endpoint group-key) {:name name} (auth-headers)
    (fn [entity]
      (swap! app-state update group-key #(sort-by-modified (conj % entity)))
      (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error
             (get-in resp [:response :error]
                     (str "Failed to add " (name (:type (constants/category-key->group group-key)))))))))

(defn update-category [app-state auth-headers fetch-tasks-fn group-key
                       id name description tags badge-title expected-modified-at
                       on-success on-error]
  (api/put-json (str (endpoint group-key) id)
    (cond-> {:name name :description description :tags tags :badge-title badge-title}
      expected-modified-at (assoc :expected-modified-at expected-modified-at))
    (auth-headers)
    (fn [updated]
      (swap! app-state update group-key
             #(sort-by-modified (mapv (fn [item] (if (= (:id item) id) updated item)) %)))
      (fetch-tasks-fn)
      (when on-success (on-success)))
    (or on-error
        (fn [resp]
          (swap! app-state assoc :error
                 (get-in resp [:response :error] "Failed to update category"))))))

(defn set-confirm-delete-category [app-state category-type category]
  (swap! app-state assoc :confirm-delete-category {:type category-type :category category}))

(defn clear-confirm-delete-category [app-state]
  (swap! app-state assoc :confirm-delete-category nil))

(defn delete-category [app-state auth-headers fetch-tasks-fn group-key id]
  (api/delete-simple (str (endpoint group-key) id)
    (auth-headers)
    (fn [_]
      (swap! app-state update group-key
             (fn [items] (filterv #(not= (:id %) id) items)))
      (fetch-tasks-fn)
      (clear-confirm-delete-category app-state))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to delete category"))
      (clear-confirm-delete-category app-state))))

(defn set-category-group
  "Move a category into another Group. The item keeps its id and all its
  associations, so the only client-side work is reloading the group it left and
  the group it joined — and the entity lists, whose badges are grouped by type."
  [app-state auth-headers fetch-tasks-fn from-key to-type id on-success]
  (let [to-key (constants/category-type->key to-type)]
    (api/put-json (str "/api/categories/" id "/group")
      {:group to-type}
      (auth-headers)
      (fn [_]
        (fetch-categories app-state auth-headers from-key)
        (when (not= from-key to-key)
          (fetch-categories app-state auth-headers to-key))
        (fetch-tasks-fn)
        (when on-success (on-success)))
      (fn [resp]
        (swap! app-state assoc :error
               (get-in resp [:response :error] "Failed to change group"))))))

(defn set-editing-category [app-state category-type id]
  (swap! app-state assoc :category-page/editing {:type category-type :id id}))

(defn clear-editing-category [app-state]
  (swap! app-state assoc :category-page/editing nil))

(defn set-drag-category [app-state category-type category-id]
  (swap! app-state assoc :drag-category {:type category-type :id category-id}))

(defn set-drag-over-category [app-state category-type category-id]
  (swap! app-state assoc :drag-over-category {:type category-type :id category-id}))

(defn clear-category-drag-state [app-state]
  (swap! app-state assoc :drag-category nil :drag-over-category nil))

(defn reorder-category [app-state auth-headers group-key category-id target-category-id position]
  (api/post-json (str (endpoint group-key) category-id "/reorder")
    {:target-category-id target-category-id :position position}
    (auth-headers)
    (fn [_]
      (clear-category-drag-state app-state)
      (fetch-categories app-state auth-headers group-key))
    (fn [resp]
      (clear-category-drag-state app-state)
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to reorder")))))
