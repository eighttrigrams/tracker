(ns et.tr.ui.state.exclusions
  (:require [clojure.string :as str]
            [et.tr.ui.constants :as constants]))

(def groups
  "The four negative category filter groups. :state-key holds the excluded ids,
  :list-key the category list they resolve against, :param the query param the
  API takes."
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

(defn toggle [app-state category-type id]
  (when-let [k (type->state-key category-type)]
    (swap! app-state update k #(if (contains? % id) (disj % id) (conj % id)))))

(defn clear [app-state]
  (swap! app-state merge (zipmap (state-keys) (repeat #{}))))

(defn excluded-categories
  "The negative filters as badge-shaped maps for the sidebar, grouped by type in
  the groups' order. Only the shift-clicked seeds appear — the rule closure they
  expand into lives in the backend and is never shown."
  [app-state]
  (vec (for [{:keys [type state-key list-key]} groups
             :let [ids (get @app-state state-key)]
             category (get @app-state list-key)
             :when (contains? ids (:id category))]
         (assoc category :type type))))

(defn query-params
  "The `excluded-*` query params as \"name=value\" strings, for each URL builder
  to join into its own shape. Read straight from app-state rather than from
  fetch opts: unlike the positive filters no caller ever passes a doctored set,
  and every filtered list must carry them. Ids resolve to names against the
  in-memory category lists — the API takes seed names and expands them through
  the category rules itself."
  [app-state]
  (vec (for [{:keys [state-key list-key param]} groups
             :let [ids (get @app-state state-key)
                   names (when (seq ids)
                           (->> (get @app-state list-key)
                                (filter #(contains? ids (:id %)))
                                (mapv :name)))]
             :when (seq names)]
         (str param "=" (js/encodeURIComponent (str/join "," names))))))
