(ns ui.modals.link-context-issue
  (:require [reagent.core :as r]))

(defn- get-component-el []
  (.getElementById js/document "link-context-issue-component"))

(def state (atom #{}))

(defn component [selected-context issue]
  (reset! state
          (doall
           (->>
            (conj (:secondary_contexts selected-context) 
                  [(:id selected-context) (:title selected-context)])
            (filter (fn [[idx _title]]
                      (contains? (set (keys (:contexts issue))) idx)))
            (map (fn [[idx _title]] idx))
            (into #{}))))
  (r/create-class
   {:component-did-mount #(.focus (get-component-el))
    :reagent-render ;
    (fn [_selected-context issue]
      [:div#link-context-issue-component
       {:tabIndex 0}
       (doall
        (map 
         (fn [[idx title]]
           [:div
            {:key idx}
            title
            [:input
             {:key            idx
              :on-change      #(swap! state 
                                      (fn [vals] ((if (contains? vals idx) disj conj) vals idx)))
              :type           :checkbox
              :defaultChecked (contains? (set (keys (:contexts issue))) idx)}]])
         (conj (:secondary_contexts selected-context) 
               [(:id selected-context) (:title selected-context)])))])}))

(defn get-values []
  @state)
