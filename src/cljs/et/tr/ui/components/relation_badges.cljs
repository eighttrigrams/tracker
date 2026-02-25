(ns et.tr.ui.components.relation-badges
  (:require [et.tr.ui.state :as state]))

(defn- relation-type-label [type]
  (case type
    "tsk" "T"
    "res" "R"
    "met" "M"
    "?"))

(defn- relation-key [relation]
  (str (:type relation) "-" (:id relation)))

(defn relation-badge-collapsed [relation]
  [:span.tag.relation.clickable
   {:key (relation-key relation)
    :on-click (fn [e]
                (.stopPropagation e)
                (state/open-relation-in-modal (:type relation) (:id relation)))}
   (str (relation-type-label (:type relation)) ": " (:title relation))])

(defn relation-badges-collapsed [relations source-type source-id]
  (when (seq relations)
    (into [:<>]
          (for [relation relations]
            ^{:key (relation-key relation)}
            [relation-badge-collapsed relation]))))

(defn relation-badge-expanded [relation source-type source-id]
  [:span.tag.relation
   {:key (relation-key relation)}
   (str (relation-type-label (:type relation)) ": " (:title relation))
   [:button.remove-tag
    {:on-click (fn [e]
                 (.stopPropagation e)
                 (state/delete-relation source-type source-id (:type relation) (:id relation)))}
    "×"]])

(defn relation-badges-expanded [relations source-type source-id]
  (when (seq relations)
    [:div.relation-badges-expanded
     (for [relation relations]
       ^{:key (relation-key relation)}
       [relation-badge-expanded relation source-type source-id])]))
