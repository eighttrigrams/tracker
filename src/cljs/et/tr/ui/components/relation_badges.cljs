(ns et.tr.ui.components.relation-badges
  (:require [et.tr.ui.state :as state]))

(defn- item-type->prefix [entity-type]
  (case entity-type
    :task "tsk"
    :resource "rsc"
    :meet "met"))

(defn relation-badges-expanded
  [{:keys [entity-type entity on-remove]}]
  (let [relations (:relations entity)
        source-type (item-type->prefix entity-type)]
    (when (seq relations)
      [:div.relation-badges-expanded
       [:span.label "Relations:"]
       (for [relation relations]
         ^{:key (str "rel-" (:target_type relation) "-" (:target_id relation))}
         [:span.tag.relation
          (:title relation)
          [:button.remove-tag
           {:on-click (fn [e]
                        (.stopPropagation e)
                        (state/delete-relation
                         source-type (:id entity)
                         (:target_type relation) (:target_id relation)
                         on-remove))}
           "×"]])])))
