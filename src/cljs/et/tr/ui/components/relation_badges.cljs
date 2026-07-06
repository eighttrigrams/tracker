(ns et.tr.ui.components.relation-badges
  (:require [clojure.string :as str]
            [et.tr.ui.date :as date]
            [et.tr.ui.state :as state]))

(defn relation-type-label [type]
  (case type
    "tsk" "T"
    "res" "R"
    "met" "M"
    "jen" "J"
    "iss" "I"
    "?"))

(defn- relation-key [relation]
  (str (:type relation) "-" (:id relation)))

(defn- relation-display-title [relation]
  (let [bt (:badge_title relation)]
    (if (and bt (not (str/blank? bt))) bt (:title relation))))

(defn- relation-task-done? [relation]
  (and (= "tsk" (:type relation)) (= 1 (:done relation))))

(defn- relation-meet-past? [relation]
  (and (= "met" (:type relation))
       (let [sd (:start_date relation)]
         (and sd (not (str/blank? sd)) (neg? (compare sd (date/today-str)))))))

(defn- relation-muted? [relation]
  (or (relation-task-done? relation) (relation-meet-past? relation)))

(def ^:private icon-svg-attrs
  {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
   :stroke-width "2" :stroke-linecap "round" :stroke-linejoin "round"
   :aria-hidden "true"})

(defn- relation-icon [type]
  [:span.relation-icon {:class type}
   (case type
     "met" [:svg icon-svg-attrs
            [:rect {:x "3" :y "4" :width "18" :height "18" :rx "2"}]
            [:line {:x1 "16" :y1 "2" :x2 "16" :y2 "6"}]
            [:line {:x1 "8" :y1 "2" :x2 "8" :y2 "6"}]
            [:line {:x1 "3" :y1 "10" :x2 "21" :y2 "10"}]]
     "res" [:svg icon-svg-attrs
            [:path {:d "M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"}]
            [:polyline {:points "14 2 14 8 20 8"}]
            [:line {:x1 "16" :y1 "13" :x2 "8" :y2 "13"}]
            [:line {:x1 "16" :y1 "17" :x2 "8" :y2 "17"}]
            [:line {:x1 "10" :y1 "9" :x2 "8" :y2 "9"}]]
     "jen" [:svg icon-svg-attrs
            [:path {:d "M17 3a2.828 2.828 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5L17 3z"}]]
     "iss" [:svg icon-svg-attrs
            [:circle {:cx "12" :cy "12" :r "9"}]
            [:line {:x1 "12" :y1 "8" :x2 "12" :y2 "13"}]
            [:line {:x1 "12" :y1 "16" :x2 "12" :y2 "16"}]]
     nil)])

(defn- relation-prefix [relation]
  (if (= "tsk" (:type relation))
    (if (= 1 (:done relation)) "☑ " "☐ ")
    (relation-icon (:type relation))))

(defn- sort-relations [relations]
  (sort-by #(if (relation-task-done? %) 0 1) relations))

(defn relation-badge-collapsed [relation]
  [:span.tag.relation.clickable
   {:key (relation-key relation)
    :class (when (relation-muted? relation) "task-done")
    :on-click (fn [e]
                (.stopPropagation e)
                (state/open-relation-in-modal (:type relation) (:id relation)))}
   (relation-prefix relation)
   (relation-display-title relation)])

(defn relation-badges-collapsed [relations source-type source-id]
  (when (seq relations)
    (into [:div.relation-badges-collapsed]
          (for [relation (sort-relations relations)]
            ^{:key (relation-key relation)}
            [relation-badge-collapsed relation]))))

(defn relation-badge-expanded [relation source-type source-id]
  [:span.tag.relation
   {:key (relation-key relation)
    :class (when (relation-muted? relation) "task-done")}
   (relation-prefix relation)
   (relation-display-title relation)
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
