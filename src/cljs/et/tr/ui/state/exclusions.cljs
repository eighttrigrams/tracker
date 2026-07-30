(ns et.tr.ui.state.exclusions
  (:require [clojure.string :as str]
            [et.tr.ui.constants :as constants]))

(def groups
  "The four negative category filter groups. :state-key holds the excluded
  categories as an id->name map, :list-key the category list they belong to,
  :param the query param the API takes."
  [{:type constants/CATEGORY-TYPE-PERSON  :state-key :shared/exclude-people   :list-key :people   :param "excluded-people"}
   {:type constants/CATEGORY-TYPE-PLACE   :state-key :shared/exclude-places   :list-key :places   :param "excluded-places"}
   {:type constants/CATEGORY-TYPE-PROJECT :state-key :shared/exclude-projects :list-key :projects :param "excluded-projects"}
   {:type constants/CATEGORY-TYPE-GOAL    :state-key :shared/exclude-goals    :list-key :goals    :param "excluded-goals"}])

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

(defn excluded-categories
  "The negative filters as badge-shaped maps for the sidebar, grouped by type in
  the groups' order. Only the shift-clicked seeds appear — the rule closure they
  expand into lives in the backend and is never shown."
  [app-state]
  (vec (for [{:keys [type state-key]} groups
             [id category-name] (get @app-state state-key)]
         {:id id :name category-name :type type})))

(defn query-params
  "The `excluded-*` query params as \"name=value\" strings, for each URL builder
  to join into its own shape. Read straight from app-state rather than from
  fetch opts: unlike the positive filters no caller ever passes a doctored set,
  and every filtered list must carry them. The names come straight out of the
  sets, stored there at shift-click time — the API takes seed names and expands
  them through the category rules itself."
  [app-state]
  (vec (for [{:keys [state-key param]} groups
             :let [names (vals (get @app-state state-key))]
             :when (seq names)]
         (str param "=" (js/encodeURIComponent (str/join "," names))))))
