(ns et.tr.ui.components.relation-badges
  (:require [clojure.string :as str]
            [et.tr.ui.state :as state]))

(defn relation-type-label [type]
  (case type
    "tsk" "T"
    "res" "R"
    "met" "M"
    "jen" "J"
    "?"))

(defn- relation-key [relation]
  (str (:type relation) "-" (:id relation)))

(defn- relation-display-title [relation]
  (let [bt (:badge_title relation)]
    (if (and bt (not (str/blank? bt))) bt (:title relation))))

(defn- relation-task-done? [relation]
  (and (= "tsk" (:type relation)) (= 1 (:done relation))))

(defn- relation-prefix [relation]
  (if (= "tsk" (:type relation))
    (if (= 1 (:done relation)) "☑ " "☐ ")
    (str (relation-type-label (:type relation)) ": ")))

(defn- sort-relations [relations]
  (sort-by #(if (relation-task-done? %) 0 1) relations))

(defn relation-badge-collapsed [relation]
  [:span.tag.relation.clickable
   {:key (relation-key relation)
    :class (when (relation-task-done? relation) "task-done")
    :on-click (fn [e]
                (.stopPropagation e)
                (state/open-relation-in-modal (:type relation) (:id relation)))}
   (str (relation-prefix relation) (relation-display-title relation))])

(defn relation-badges-collapsed [relations source-type source-id]
  (when (seq relations)
    (into [:<>]
          (for [relation (sort-relations relations)]
            ^{:key (relation-key relation)}
            [relation-badge-collapsed relation]))))

(defn relation-badge-expanded [relation source-type source-id]
  [:span.tag.relation
   {:key (relation-key relation)
    :class (when (relation-task-done? relation) "task-done")}
   (str (relation-prefix relation) (relation-display-title relation))
   [:button.remove-tag
    {:on-click (fn [e]
                 (.stopPropagation e)
                 (state/delete-relation source-type source-id (:type relation) (:id relation)))}
    "×"]])

(defn relation-badges-expanded [relations source-type source-id]
  (when (seq relations)
    [:div.relation-badges-expanded
     (for [relation (sort-relations relations)]
       ^{:key (relation-key relation)}
       [relation-badge-expanded relation source-type source-id])]))
