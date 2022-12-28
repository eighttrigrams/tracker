(ns ui.modals.actions
  (:require api
            [ui.actions.common :refer [fetch-and-reset!]]))

(defn cancel-modal! [*state]
  (swap! *state dissoc :modal))

(defn new-issue! [*state issue]
  (fetch-and-reset! *state (-> @*state
                               (dissoc :modal)
                               (assoc :issue-to-insert issue))))

(defn save-description! [*state type item]
  (fetch-and-reset! *state (-> @*state
                               (dissoc :modal)
                               (assoc (if (= :issue type)
                                        :issue-to-update-description-of
                                        :context-to-update-description-of) item))))

(defn update-issue! [*state issue]
  (fetch-and-reset! *state 
                    (-> @*state
                        (assoc :issue-to-update issue)
                        (dissoc :modal))))

(defn update-context! [*state context]
  (fetch-and-reset! *state
                    (-> @*state
                        (assoc :context-to-update context)
                        (dissoc :modal))))

(defn update-issue-contexts! [*state values]
  (prn "update-issue-contexts!" values))
