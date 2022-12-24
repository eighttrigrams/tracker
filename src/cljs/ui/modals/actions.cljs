(ns ui.modals.actions
  (:require [cljs.core.async :refer [go]]
            [cljs.core.async.interop :refer-macros [<p!]]
            api
            [ui.actions.common :refer [fetch-and-reset!]]))

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

(defn update-issue! [*state issue]
  (fetch-and-reset! *state 
                    (-> @*state
                        (assoc :issue-to-update issue)
                        (dissoc :modal))))

