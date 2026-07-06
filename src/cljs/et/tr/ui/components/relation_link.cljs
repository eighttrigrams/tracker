(ns et.tr.ui.components.relation-link
  (:require [et.tr.ui.state :as state]))

(def ^:private item-type->collection-key
  {:task :tasks
   :resource :resources
   :meet :meets
   :journal-entry :journal-entries
   :issue :issues})

(defn- belongs-to-linked?
  "task↔issue membership lives in the task's issue_id FK, not in (:relations item),
  so the generic :relations scan below misses it. Detect it explicitly in both
  directions."
  [source item-type item]
  (cond
    (and (= (:type source) "tsk") (= item-type :issue))
    (boolean (some #(= (:id %) (:id source)) (:tasks item)))

    (and (= (:type source) "iss") (= item-type :task))
    (= (:issue_id item) (:id source))

    :else false))

(defn- already-linked? [source item-type item-id]
  (when source
    (let [coll-key (item-type->collection-key item-type)
          items (get @state/*app-state coll-key)
          item (first (filter #(= (:id %) item-id) items))]
      (or (some #(and (= (:type %) (:type source))
                      (= (:id %) (:id source)))
                (:relations item))
          (belongs-to-linked? source item-type item)))))

(defn relation-link-button [item-type item-id]
  (when (state/relation-mode-active?)
    (let [source (state/relation-source)]
      (when-not (already-linked? source item-type item-id)
        (let [is-source? (and source
                              (= (:type source) (state/item-type->prefix item-type))
                              (= (:id source) item-id))]
          [:button.relation-link-btn
           {:class (when is-source? "source-selected")
            :on-click (fn [e]
                        (.stopPropagation e)
                        (when-not is-source?
                          (state/select-for-relation item-type item-id)))}
           "◎"])))))
