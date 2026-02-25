(ns et.tr.ui.components.relation-badges
  (:require [et.tr.ui.state :as state]))

(defn- relation-type-label [type]
  (case type
    "tsk" "T"
    "res" "R"
    "met" "M"
    "?"))

(defn relation-badge-collapsed [relation source-type source-id]
  [:span.tag.relation
   {:key (str (:type relation) "-" (:id relation))}
   (str (relation-type-label (:type relation)) ": " (:title relation))])

(defn relation-badges-collapsed [relations source-type source-id]
  (when (seq relations)
    (into [:<>]
          (for [relation relations]
            ^{:key (str (:type relation) "-" (:id relation))}
            [relation-badge-collapsed relation source-type source-id]))))

(defn relation-badge-expanded [relation source-type source-id]
  [:span.tag.relation
   {:key (str (:type relation) "-" (:id relation))}
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
       ^{:key (str (:type relation) "-" (:id relation))}
       [relation-badge-expanded relation source-type source-id])]))
