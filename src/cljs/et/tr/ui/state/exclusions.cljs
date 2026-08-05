(ns et.tr.ui.state.exclusions
  (:require [clojure.string :as str]
            [et.tr.ui.constants :as constants]))

(def groups
  "One negative category filter group per Category Group. :state-key holds the
  excluded categories as an id->name map, :list-key the category list they
  belong to, :param the query param the API takes. Derived from the one registry
  so a new Group gets its negative filter for free."
  (mapv (fn [{:keys [type key]}]
          {:type type
           :state-key (keyword "shared" (str "exclude-" (name key)))
           :list-key key
           :param (str "excluded-" (name key))})
        constants/category-groups))

(def ^:private type->state-key
  (into {} (map (juxt :type :state-key) groups)))

(defn state-keys []
  (mapv :state-key groups))

(defn active?
  "True while any negative filter is set — the flag that replaces the sidebar's
  four groups and locks positive filtering out."
  [app-state]
  (boolean (some #(seq (get @app-state (:state-key %))) groups)))

(defn toggle
  "The name is stored with the id because the badge under the shift-click is the
  only place it is reliably available: the in-memory category lists load once at
  app start, so a category another client created since is not in them."
  [app-state category-type id category-name]
  (when-let [k (type->state-key category-type)]
    (swap! app-state update k #(if (contains? % id) (dissoc % id) (assoc % id category-name)))))

(defn clear [app-state]
  (swap! app-state merge (zipmap (state-keys) (repeat {}))))

(defn- current-name
  "The name to filter and label by. The stored one is a snapshot taken at
  shift-click time, so a rename made in this client would leave it pointing at a
  category the API no longer knows; the in-memory list is the live copy and wins
  wherever it knows the id. It only knows categories that existed at app start,
  hence the fallback."
  [app-state list-key id stored-name]
  (or (some #(when (= id (:id %)) (:name %)) (get @app-state list-key))
      stored-name))

(defn excluded-categories
  "The negative filters as badge-shaped maps for the sidebar, grouped by type in
  the groups' order. Only the shift-clicked seeds appear — the rule closure they
  expand into lives in the backend and is never shown."
  [app-state]
  (vec (for [{:keys [type state-key list-key]} groups
             [id stored-name] (get @app-state state-key)]
         {:id id :name (current-name app-state list-key id stored-name) :type type})))

(defn query-params
  "The `excluded-*` query params as \"name=value\" strings, for each URL builder
  to join into its own shape. Read straight from app-state rather than from fetch
  opts, which makes the excludes global: they apply even to a caller that passes
  a doctored opts map to sidestep the positive filters. `state/focus-issue` is
  the one such caller today, and its focused-issue task listing is meant to
  honour them — the chips stay on screen in the issues sidebar while it is up.
  The API takes seed names and expands them through the category rules itself."
  [app-state]
  (vec (for [{:keys [state-key list-key param]} groups
             :let [names (for [[id stored-name] (get @app-state state-key)]
                           (current-name app-state list-key id stored-name))]
             :when (seq names)]
         (str param "=" (js/encodeURIComponent (str/join "," names))))))
