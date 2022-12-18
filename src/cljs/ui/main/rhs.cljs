(ns ui.main.rhs
  (:require [reagent.core :as r]
            repository
            [ui.actions :as actions]))

(defn- search! [*state]
  (fn [e]
    (repository/fetch! @*state (.-value (.-target e))
                       #(reset! *state %))))

(defn- select-issue! [*state issue]
  (fn [_e]
    (if (:active-search @*state)
      (repository/fetch! @*state ""
                         #(reset! *state 
                                  (-> %
                                      (dissoc :active-search)
                                      (assoc :selected-issue issue))))
      (swap! *state (fn [old-state]
                      (assoc old-state :selected-issue issue))))))

(defn quit-search! [*state]
  (fn [_e]
    (actions/quit-search! *state)))

(defn- input-component [*state]
  (r/create-class 
   {:component-did-mount #(.focus (.getElementById js/document "issues-search-input"))
    :render (fn []
              [:input#issues-search-input
               {:on-change (search! *state)
                :on-key-down (fn [e] (let [code (.-code e)]
                                       (.stopPropagation e)
                                       (when (= code "Escape")
                                         (actions/quit-search! *state))))}])}))

(defn- issues-list [*state]
  [:ul
   {:class (when (= :issues (:active-search @*state)) :active-search-list)}
   (doall (for [issue (:issues @*state)]
            ^{:key (:id issue)}
            [:li 
             {:class    (when (= (:selected-issue @*state) issue) :selected)
              :on-click (select-issue! *state issue)}
             (:title issue)]))])

(defn component [_*state]
  (fn [*state]
    [:<>
     (when (= :issues (:active-search @*state))
       [:<>
        [:div.active-search-input-container [input-component *state]]
        [:div.mask.mask-active-search
         {:on-click (quit-search! *state)}]])
     [issues-list *state]]))
