(ns ui.modals.issue-edit
  (:require [reagent.core :as r]))

(defn- get-title-el []
  (.getElementById js/document "issue-title"))

(defn- get-short-title-el []
  (.getElementById js/document "issue-short-title"))

(defn- get-tags-el []
  (.getElementById js/document "issue-tags"))

(defn component [item]
  (r/create-class
   {:component-did-mount #(.focus (get-title-el))
    :reagent-render      ;
    (fn [_item]
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
          :defaultValue (:tags item)}]]])}))

(defn get-values []
  {:title       (.-value (get-title-el))
   :short_title (.-value (get-short-title-el))
   :tags        (.-value (get-tags-el))})
