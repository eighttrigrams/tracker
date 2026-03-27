(ns et.tr.ui.components.relation-link
  (:require [et.tr.ui.state :as state]))

(defn- task-ineligible-for-today? [task]
  (or (= 1 (:today task))
      (:due_date task)))

(def ^:private item-type->collection-key
  {:task :tasks
   :resource :resources
   :meet :meets})

(defn- already-linked? [source item-type item-id]
  (when source
    (let [coll-key (item-type->collection-key item-type)
          items (get @state/*app-state coll-key)
          item (first (filter #(= (:id %) item-id) items))]
      (some #(and (= (:type %) (:type source))
                  (= (:id %) (:id source)))
            (:relations item)))))

(defn relation-link-button [item-type item-id]
  (when (state/relation-mode-active?)
    (let [source (state/relation-source)]
      (when-not (or (and (= "today" (:type source))
                         (or (not= :task item-type)
                             (when-let [task (first (filter #(= (:id %) item-id) (:tasks @state/*app-state)))]
                               (task-ineligible-for-today? task))))
                    (already-linked? source item-type item-id))
        (let [is-source? (and source
                              (= (:type source) (state/item-type->prefix item-type))
                              (= (:id source) item-id))]
          [:button.relation-link-btn
           {:class (when is-source? "source-selected")
            :on-click (fn [e]
                        (.stopPropagation e)
                        (state/select-for-relation item-type item-id))}
           "◎"])))))
