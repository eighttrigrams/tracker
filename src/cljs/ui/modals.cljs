(ns ui.modals
  (:require [reagent.core :as r]
            [ui.key-handler :as key-handler]))

(defn- textarea-component [_item]
  (r/create-class
   {:component-did-mount #(.focus (.getElementById js/document "description-editor"))
    :reagent-render (fn [item]
                      [:textarea#description-editor
                       {:defaultValue (:description item)}])}))

(defn- new-issue-component []
  (r/create-class
   {:component-did-mount #(.focus (.getElementById js/document "new-issue"))
    :reagent-render (fn [_]
                      [:input#new-issue
                       {:autoComplete :off}])}))

(defn- key-handler [*state item]
  (case (:modal @*state)
    :description
    (key-handler/handle-modal-keys *state 
                                   (:id item) 
                                   #(.-value (.getElementById js/document "description-editor")))
    :new-issue
    (key-handler/handle-modal-keys *state 
                                   :new
                                   #(.-value (.getElementById js/document "new-issue")))
    #()))

(defn component [*state]
  (fn [_*state]
    (let [item (if (:selected-issue @*state)
                 (:selected-issue @*state)
                 (:selected-context @*state))]
      [:div
       {:on-key-down (key-handler *state item)}
       (case (:modal @*state)
         :description
         [textarea-component item]
         :new-issue
         [new-issue-component]
         nil)])))
