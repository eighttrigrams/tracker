(ns ui.modals.context-edit
  (:require [reagent.core :as r]))

(defn- get-title-el []
  (.getElementById js/document "context-title"))

(defn- get-short-title-el []
  (.getElementById js/document "context-short-title"))

(defn- get-tags-el []
  (.getElementById js/document "context-tags")) ;; TODO maybe just name it "tags"

(defn component [_context]
  (r/create-class 
   {:component-did-mount #(.focus (get-title-el))
    :reagent-render
    (fn [context]
      [:<> 
       [:div
        [:input#context-title
         {:autoComplete :off
          :defaultValue (:title context)}]]
       [:div
        [:input#context-short-title
         {:autoComplete :off
          :defaultValue (:short_title context)}]] ;; TODO work with short-title
       [:div
        [:input#context-tags
         {:autoComplete :off
          :defaultValue (:tags context)}]]])}))

(defn get-values [id]
  {:id          id
   :title       (.-value (get-title-el))
   :short_title (.-value (get-short-title-el))
   :tags        (.-value (get-tags-el))})
