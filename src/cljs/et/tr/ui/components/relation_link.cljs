(ns et.tr.ui.components.relation-link
  (:require [et.tr.ui.state :as state]
            [et.tr.ui.state.relations :as relations-state]))

(defn relation-link-button [entity-type entity]
  (let [relation-mode (state/relation-mode?)
        relation-source (state/relation-source)
        is-source (and relation-source
                       (= (:type relation-source) (case entity-type
                                                    :task "tsk"
                                                    :resource "rsc"
                                                    :meet "met"))
                       (= (:id relation-source) (:id entity)))]
    (when relation-mode
      [:button.relation-link-btn
       {:class (when is-source "source")
        :on-click (fn [e]
                    (.stopPropagation e)
                    (state/select-for-relation entity-type entity))
        :title (if is-source "Source item" "Click to link")}
       [:svg {:width "12" :height "12" :viewBox "0 0 12 12"}
        [:circle {:cx "6" :cy "6" :r "4"
                  :fill "none"
                  :stroke "currentColor"
                  :stroke-width "1.5"}]
        [:circle {:cx "6" :cy "6" :r "1.5"
                  :fill "currentColor"}]]])))
