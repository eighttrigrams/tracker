(ns et.tr.ui.components.relation-link
  (:require [et.tr.ui.state :as state]))

(defn relation-link-button [item-type item-id]
  (when (state/relation-mode-active?)
    (let [source (state/relation-source)
          is-source? (and source
                          (= (:type source) (state/item-type->prefix item-type))
                          (= (:id source) item-id))]
      [:button.relation-link-btn
       {:class (when is-source? "source-selected")
        :on-click (fn [e]
                    (.stopPropagation e)
                    (state/select-for-relation item-type item-id))}
       "◎"])))
