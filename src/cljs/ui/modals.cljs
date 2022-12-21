(ns ui.modals
  (:require [reagent.core :as r]
            [ui.key-handler :as key-handler]
            [net.eighttrigrams.cljs-text-editor.editor :as editor]))

(defn- get-description-el []
  (.getElementById js/document "description-editor"))

(defn- get-input-el []
  (.getElementById js/document "new-issue"))

(defn- textarea-component [_item]
  (r/create-class
   {:component-did-mount ;
    #(let [el (get-description-el)]
       (editor/create el {})
       (.focus el))
    :reagent-render (fn [item]
                      [:textarea#description-editor
                       {:defaultValue (:description item)}])}))

(defn- new-issue-component []
  (r/create-class
   {:component-did-mount #(.focus (get-input-el))
    :reagent-render (fn [_]
                      [:input#new-issue
                       {:autoComplete :off}])}))

(defn- key-handler [*state item]
  (case (:modal @*state)
    :description
    (key-handler/handle-modal-keys *state 
                                   (:id item) 
                                   #(.-value (get-description-el)))
    :new-issue
    (key-handler/handle-modal-keys *state 
                                   :new
                                   #(.-value (get-input-el)))
    #()))

(defn component [*state]
  (fn [_*state]
    (let [item (if (:selected-issue @*state)
                 (:selected-issue @*state)
                 (:selected-context @*state))]
      [:div
       {:on-key-down (key-handler *state item)
        :on-click #(.stopPropagation %)}
       (case (:modal @*state)
         :description
         [textarea-component item]
         :new-issue
         [new-issue-component]
         nil)])))
