(ns ui.modals.issue-edit
  (:require [reagent.core :as r]))

(defn- get-title-el []
  (.getElementById js/document "issue-title"))

(defn- get-short-title-el []
  (.getElementById js/document "issue-short-title"))

(defn- get-tags-el []
  (.getElementById js/document "issue-tags"))

(defn- get-event-el []
  (.getElementById js/document "has-date"))

(defn- get-date-el []
  (.getElementById js/document "date-picker"))

(defn component [item]
  (let [date-visible? (r/atom (boolean (:date item)))]
    (r/create-class
     {:component-did-mount #(.focus (get-title-el))
      :reagent-render      ;
      (fn [_item]
        (prn (when (get-date-el) (.-value (get-date-el))))
        [:<>
         [:div
          [:input#issue-title
           {:autoComplete :off
            :defaultValue (:title item)}]]
         [:div
          [:input#issue-short-title
           {:autoComplete :off
            :defaultValue (:short_title item)}]] ;; TODO work with short-title
         [:div
          [:input#issue-tags
           {:autoComplete :off
            :defaultValue (:tags item)}]]
         [:div
          [:input#has-date
           {:type :checkbox
            :defaultChecked @date-visible?
            :on-click #(swap! date-visible? not)}]]
         (when @date-visible?
           [:div
            [:input#date-picker
             {:type :date
              :defaultValue (:date item)}]])])})))

(defn get-values []
  {:title       (.-value (get-title-el))
   :short_title (.-value (get-short-title-el))
   :tags        (.-value (get-tags-el))
   :has-event?  (.-checked (get-event-el))
   :date        (when (get-date-el) (.-value (get-date-el)))})
