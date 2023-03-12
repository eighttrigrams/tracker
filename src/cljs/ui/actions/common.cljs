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
                             quit-active-search?
                             selected-secondary-contexts-ids
                             active-search] :as i} 
                     state]
  (->
   state
   (assoc :issues (or issues (:issues state))) 
   (assoc :contexts (or contexts (:contexts state)))
   (assoc :selected-issue selected-issue)
   (assoc :selected-context selected-context)
   (assoc :selected-secondary-contexts-ids selected-secondary-contexts-ids)
   (assoc :active-search active-search)
   (dissoc :cmd
           :arg
           :context-to-fetch
           :link-issue-contexts)))

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
