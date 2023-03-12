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
  (prn selected-secondary-contexts-ids)
  (->
   state
   (assoc :issues (or issues (:issues state))) ;; TODO get rid of this or; decide on backend what to set here and set it always, like for example :active-search; get to a stage where we almost completely overwrite the local state by the one fetched from the server
   (assoc :contexts (or contexts (:contexts state)))
   (assoc :selected-issue (or selected-issue (:selected-issue state)))
   (assoc :selected-context (or selected-context (:selected-context state)))
   (assoc :selected-secondary-contexts-ids selected-secondary-contexts-ids)
   (assoc :active-search active-search)
   (dissoc :cmd
           :arg
           :issue-and-related-issues-to-update
           :context-and-secondary-contexts-to-update
           :issue-to-fetch
           :context-to-fetch
           :issue-to-update-description-of
           :context-to-update-description-of
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
