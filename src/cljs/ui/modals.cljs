(ns ui.modals
  (:require [reagent.core :as r]
            [ui.key-handler :as key-handler]))

(defn- textarea-component [_*state]
  (r/create-class
   {:component-did-mount #(.focus (.getElementById js/document "description-editor"))
    :reagent-render (fn [] 
                      [:textarea#description-editor])}))

(defn component [*state]
  [:div
   {:on-key-down (key-handler/handle-modal-keys *state)}
   [textarea-component *state]])
