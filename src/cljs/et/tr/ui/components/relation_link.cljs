(ns et.tr.ui.components.relation-link
  (:require [et.tr.ui.state :as state]))

(defn- task-ineligible-for-today? [task]
  (or (= 1 (:today task))
      (:due_date task)))

(defn relation-link-button [item-type item-id]
  (when (state/relation-mode-active?)
    (let [source (state/relation-source)]
      (when-not (and (= "today" (:type source))
                     (or (not= :task item-type)
                         (when-let [task (first (filter #(= (:id %) item-id) (:tasks @state/*app-state)))]
                           (task-ineligible-for-today? task))))
        (let [is-source? (and source
                              (= (:type source) (state/item-type->prefix item-type))
                              (= (:id source) item-id))]
          [:button.relation-link-btn
           {:class (when is-source? "source-selected")
            :on-click (fn [e]
                        (.stopPropagation e)
                        (state/select-for-relation item-type item-id))}
           "◎"])))))
