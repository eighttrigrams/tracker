(ns ui
  (:require [reagent.core :as r] 
            [ui.key-handler :as key-handler]
            [ui.main :as main]
            [ui.modals :as modals]))

(def original-state {:issues                          []
                     :contexts                        []
                     :selected-context                nil
                     :selected-secondary-contexts-ids #{}
                     :selected-issue                  nil
                     :show-events?                    false
                     ;; nil|:issues|:contexts
                     :active-search                   nil
                     :modal                           nil})

(defn re-focus []
  (when-let [el (.getElementById js/document "main-layer")]
    (.focus el)))

(defn- add-state-watch [*state]
  (add-watch *state
             :on-state-change
             (fn [_ _ old-state new-state]
               (when (or (and (:active-search old-state)
                              (not (:active-search new-state)))
                         (and (:modal old-state) ;; TODO extract duplicate pattern
                              (not (:modal new-state))))
                 (re-focus)))))

(defn component []
  (let [*state (r/atom original-state)]
    (add-state-watch *state)
    (r/create-class
     {:component-did-mount re-focus
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
              [:div#modals-component
               [modals/component *state]]]])]])})))