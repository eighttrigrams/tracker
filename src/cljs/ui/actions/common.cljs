(ns ui.actions.common
  (:require [cljs.core.async :refer [go]]
            [cljs.core.async.interop :refer-macros [<p!]]
            api))

(defn reset-state! [new-state *state]
  (reset! *state new-state))

(defn- update-state [{:keys [issues 
                             contexts 
                             selected-issue 
                             selected-context
                             quit-active-search?]} 
                     state]
  (->
   state
   (assoc :issues (or issues (:issues state))) ;; TODO simplify by having just one assoc
   (assoc :contexts (or contexts (:contexts state)))
   (assoc :selected-issue (or selected-issue (:selected-issue state)))
   (assoc :selected-context (or selected-context (:selected-context state)))
   (#(if quit-active-search? (dissoc % :active-search) %))
   (dissoc :issue-to-update
           :context-to-update
           :issue-to-fetch
           :issue-to-insert
           :issue-to-update-description-of
           :context-to-update-description-of)))

(defn- list-resources [state q]
  (api/list-resources
   (-> state
       (dissoc :issues :contexts)
       (assoc :q q))))

(defn- fetch-resources
  [state value]
  (go (-> state
          (list-resources value)
          <p!
          (update-state state))))

(defn fetch-and-reset!
  ([*state state] (fetch-and-reset! *state state ""))
  ([*state state value]
   (go (-> state
           (fetch-resources value)
           <!
           (reset-state! *state)))))
