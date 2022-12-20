(ns ui.actions
  (:require [cljs.core.async :refer [go]]
            [cljs.core.async.interop :refer-macros [<p!]]
            api))

;; TODO this doesn't seem to belong here
(defn re-focus []
  (when-let [el (.getElementById js/document "main-layer")]
    (.focus el)))

(defn reset-state! [new-state *state]
  (reset! *state new-state))

(defn- update-state [{:keys [issues contexts]} state]
  (->
   state
   (assoc :issues (or issues (:issues state)))
   (assoc :contexts (or contexts (:contexts state)))))

(defn- list-resources [state q]
  (api/list-resources
   {:q                   q
    :active-search       (:active-search state)
          ;; TODO simplify: pass through the state (minus unecessary big parts)
    :selected-context-id (:id (:selected-context state))}) ;; TODO pass selected-context instead only id 
  )

(defn fetch! [*state]
  (go (-> @*state
          (list-resources "")
          <p!
          (update-state @*state)
          (reset-state! *state))))

(defn quit-search! [*state]
  (go (-> @*state
          (list-resources "")
          <p!
          (update-state @*state)
          (dissoc :active-search)
          (reset-state! *state))
      (re-focus)))

(defn deselect-context! [*state]
  (let [state (-> @*state
                  (dissoc :selected-context))]
    (go (-> @*state
            (list-resources "")
            <p!
            (update-state state)
            (reset-state! *state)))))

(defn- select-item! [*state item key]
  (let [state (-> @*state
                  (assoc key item)
                  (dissoc :active-search))]
    (go (-> state
            (assoc key item)
            (list-resources "")
            <p!
            (update-state state)
            (dissoc :active-search)
            (reset-state! *state)))))

(defn select-context! [*state context]
  (select-item! *state context :selected-context))

(defn select-issue! [*state issue]
  (select-item! *state issue :selected-issue))

(defn search! [*state value]
  (go (let [new-state (update-state (<p! (list-resources @*state value)) @*state)]
        (prn (keys new-state))
        (reset! *state new-state))))

(defn cancel-modal! [*state]
  (swap! *state dissoc :modal)
  (re-focus))

(defn new-issue! [*state value]
  (go (let [new-issue (<p! (api/new-issue value (-> @*state :selected-context :id)))
            result    (<p! (list-resources @*state ""))]
        (reset! *state
                (-> result
                    (update-state @*state)
                    (dissoc :modal)
                    (assoc :selected-issue new-issue)))
        (re-focus))))

(defn save-description! [*state type id value]
  (go (let [updated-item (<p! ((if (= type :issue)
                                 api/save-issue ;; TODO switch in the backend
                                 api/save-context) id value))
            result       (<p! (list-resources @*state ""))] 
        (reset! *state 
                (-> result
                    (update-state @*state)
                    (dissoc :modal)
                    (assoc (if (= :issue type)
                             :selected-issue
                             :selected-context) updated-item)))
        (re-focus))))
