(ns ui.modals
  (:require [reagent.core :as r]
            [ui.key-handler :as key-handler]))

(defn- textarea-component [_item]
  (r/create-class
   {:component-did-mount #(.focus (.getElementById js/document "description-editor"))
    :reagent-render (fn [item]
                      (prn "item" item)
                      [:textarea#description-editor
                       {:defaultValue (:description item)}])}))

(defn component [*state]
  (fn [_*state]
    (let [item (if (:selected-issue @*state)
                 (:selected-issue @*state)
                 (:selected-context @*state))]
      [:div
       {:on-key-down (key-handler/handle-modal-keys 
                      *state 
                      (:id item) 
                      #(.-value (.getElementById js/document "description-editor")))}
       [textarea-component item]])))
