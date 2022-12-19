(ns ui
  (:require [reagent.core :as r] 
            repository
            [ui.key-handler :as key-handler]
            [ui.main :as main]
            [ui.modals :as modals]
            [ui.actions :as actions]))

(def original-state {:issues                []
                     :contexts              []
                     :selected-context      nil
                     :selected-issue        nil
                     ;; nil|:issues|:contexts
                     :active-search         nil
                     :modal                 nil})

(defn component []
  (let [*state (r/atom original-state)]
    (r/create-class
     {:component-did-mount actions/re-focus
      :render              ;
      (fn []
        [:div#ui
         [:div#main-layer
          {;; TODO document recipe
           ;; to make the div able to listen to key events, https://stackoverflow.com/a/3149416
           :tabIndex    0
           :on-key-down (key-handler/handle-keys *state)}
          [main/component *state]]
         [:div#modals-layer
          (when (:modal @*state)
            [:<>
             [:div.mask 
              {:on-click #(actions/cancel-modal! *state)}
              [:div#modals-component
               [modals/component *state]]]])]])})))