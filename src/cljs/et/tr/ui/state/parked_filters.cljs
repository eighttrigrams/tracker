(ns et.tr.ui.state.parked-filters
  "The parked positive Category selection: what Option+Esc took out of the six
  sidebar Groups, kept as one bundle so it can be put back.

  Option+Esc empties the Groups as it always did — the bundle is not a second
  selection and nothing filters by it — but what came out now sits under the
  sections as one box of pills, and clicking that box puts the whole selection
  back. The bundle is dropped the moment a Category is selected anew, because
  from then on the selection on screen is the user's own and the parked one is
  a stale copy of something they moved on from.

  The live selection this parks lives under `constants/shared-filter-key`, one
  key per Group; the negative filters' counterpart is
  et.tr.ui.state.exclusions."
  (:require [et.tr.ui.constants :as constants]))

(def ^:private state-key
  "One key for all six Groups, not one per Group: the bundle is atomic. It is
  parked, restored and dropped whole, so there is no state in which half of it
  exists and nothing has to keep six keys in step."
  :shared/parked-filters)

(defn- selection
  "group-key -> #{ids}, only for the Groups that have anything selected."
  [app-state]
  (into {}
        (keep (fn [k]
                (when-let [ids (seq (get @app-state (constants/shared-filter-key k)))]
                  [k (set ids)])))
        constants/category-key-order))

(defn park!
  "Empty every Group's filter, keeping what was in them as the bundle.

  An empty selection leaves an existing bundle alone rather than overwriting it
  with nothing: pressing Option+Esc a second time must not cost what the first
  press parked, which is the whole point of parking it."
  [app-state]
  (let [parked (selection app-state)]
    (swap! app-state merge constants/cleared-shared-filters)
    (when (seq parked)
      (swap! app-state assoc state-key parked))))

(defn drop!
  "Forget the bundle. Called when a Category is selected anew — see the ns
  docstring for why that is the end of it."
  [app-state]
  (swap! app-state dissoc state-key))

(defn unpark?
  "Whether the park gesture should put the bundle back rather than park a new
  one: there is a bundle, and no Group has anything selected.

  This is what makes Option+Escape a toggle. The two readings cannot both be
  wanted at once — with nothing selected there is nothing to park, and with
  something selected the bundle is about to be dropped anyway — so the state
  decides, and the key never has to be told which one is meant."
  [app-state]
  (and (seq (get @app-state state-key))
       (empty? (selection app-state))))

(defn restore!
  "Put the bundle back into the Groups and forget it. Returns true when there
  was one, so the caller knows whether it has to refetch.

  Clears before merging, so the restored bundle is the selection rather than
  being added to one. Nothing can be selected while a bundle exists, so that
  only matters if something ever parks a partial selection — and then
  \"bring the original selection back\" still means what it says."
  [app-state]
  (when-let [parked (get @app-state state-key)]
    (swap! app-state
           (fn [s]
             (-> (merge s constants/cleared-shared-filters)
                 (merge (into {}
                              (map (fn [[k ids]] [(constants/shared-filter-key k) ids]))
                              parked))
                 (dissoc state-key))))
    true))

(defn prune-group!
  "Drop parked ids of one Group that are not in `in-scope-ids`, and the whole
  bundle once its last Group is gone.

  Called from the scope change that prunes the live selection the same way: a
  bundle that outlived a scope switch must not be able to put back a filter the
  sidebar would no longer offer."
  [app-state group-key in-scope-ids]
  (when (contains? (get @app-state state-key) group-key)
    (swap! app-state update state-key
           (fn [parked]
             (let [kept (into #{} (filter in-scope-ids) (get parked group-key))]
               (if (seq kept)
                 (assoc parked group-key kept)
                 (dissoc parked group-key)))))
    (when (empty? (get @app-state state-key))
      (swap! app-state dissoc state-key))))

(defn pills
  "The bundle as badge-shaped {:id :name :type} maps in Group order, for the box
  to render — the box shows no Group headings, so the order is what keeps one
  Group's colour together.

  Ids are resolved against the in-memory category lists, exactly as the
  sidebar's own pills are, and with the same limitation: a category another
  client created since app start is not in those lists and so has no pill. Such
  an id can only have been selected off a card badge, and it is still restored
  by a click — it is invisible in the box, not lost from it."
  [app-state]
  (let [parked (get @app-state state-key)]
    (vec (for [{:keys [key type]} constants/category-groups
               :let [ids (get parked key)]
               :when (seq ids)
               item (get @app-state key)
               :when (contains? ids (:id item))]
           {:id (:id item) :name (:name item) :type type}))))
