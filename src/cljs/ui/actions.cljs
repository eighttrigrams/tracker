(ns ui.actions
  (:require [cljs.core.async :refer [go]]
            [cljs.core.async.interop :refer-macros [<p!]]
            api))

(defn reset-state! [new-state *state]
  (reset! *state new-state))

(defn- update-state [{:keys [issues contexts]} state]
  (->
   state
   (assoc :issues (or issues (:issues state)))
   (assoc :contexts (or contexts (:contexts state)))))

(defn- list-resources [state q]
  (api/list-resources
   (assoc (select-keys state [:active-search :show-events?])
          :q q
          :selected-context-id (:id (:selected-context state)))))

(defn- fetch-resources
  [state value]
  (go (-> state
          (list-resources value)
          <p!
          (update-state state))))

(defn- fetch-and-reset! 
  ([*state state] (fetch-and-reset! *state state ""))
  ([*state state value]
   (go (-> state
           (fetch-resources value)
           <!
           (reset-state! *state)))))

(defn fetch! [*state]
  (fetch-and-reset! *state @*state))

(defn quit-search! [*state]
  (fetch-and-reset! *state (dissoc @*state :active-search)))

(defn deselect-context! [*state] 
  (fetch-and-reset! *state (-> @*state
                               (dissoc :selected-context))))

(defn- select-item! [*state item key]
  (if (or (:active-search @*state) 
          (= :selected-context key))
    (fetch-and-reset! *state (-> @*state
                                 (assoc key item)
                                 (dissoc :active-search)))
    (swap! *state #(assoc % key item))))

(defn select-context! [*state context]
  (select-item! *state context :selected-context))

(defn select-issue! [*state issue]
  (select-item! *state issue :selected-issue))

(defn search! [*state value]
  (fetch-and-reset! *state @*state value))

(defn show-events! [*state]
  (fetch-and-reset! *state (-> @*state
                               (assoc :show-events? true)
                               (dissoc :selected-context))))

(defn exit-events-view! [*state]
  (fetch-and-reset! *state (assoc @*state :show-events? false)))

(defn cancel-modal! [*state]
  (swap! *state dissoc :modal))

(defn new-issue! [*state value]
  (go (let [new-issue (<p! (api/new-issue value (-> @*state :selected-context :id)))]
        (fetch-and-reset! *state (-> @*state
                                     (dissoc :modal)
                                     (assoc :selected-issue new-issue))))))

(defn save-description! [*state type id value]
  (go (let [updated-item (<p! ((if (= type :issue)
                                 api/update-issue-description
                                 api/update-context-description) id value))] 
        (fetch-and-reset! *state (-> @*state
                                     (dissoc :modal)
                                     (assoc (if (= :issue type)
                                              :selected-issue
                                              :selected-context) updated-item))))))
