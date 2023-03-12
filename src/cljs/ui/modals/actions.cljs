(ns ui.modals.actions
  (:require api
            [ui.actions.common :refer [fetch-and-reset!]]))

(defn cancel-modal! [*state]
  (swap! *state dissoc :modal))

(defn new-issue! [*state issue]
  (fetch-and-reset! *state (-> @*state
                               (dissoc :modal)
                               (assoc :cmd :insert-issue)
                               (assoc :arg issue))))

(defn new-context! [*state context]
  (fetch-and-reset! *state (-> @*state
                               (dissoc :modal)
                               (assoc :cmd :insert-context)
                               (assoc :arg context))))

(defn save-description! [*state type item]
  (fetch-and-reset! *state (-> @*state
                               (dissoc :modal)
                               (assoc :cmd (if (= :issue type)
                                             :update-issue-description
                                             :update-context-description))
                               (assoc :arg item))))

(defn update-issue! [*state issue]
  (fetch-and-reset! *state 
                    (-> @*state
                        (assoc :cmd :update-issue)
                        (assoc :arg issue)
                        (dissoc :modal))))

(defn update-context! [*state context]
  (fetch-and-reset! *state
                    (-> @*state
                        (assoc :cmd :update-context)
                        (assoc :arg context)
                        (dissoc :modal))))

(defn update-issue-contexts! [*state values]
  (fetch-and-reset! *state
                    (-> @*state
                        (assoc :link-issue-contexts values)
                        (dissoc :modal))))
