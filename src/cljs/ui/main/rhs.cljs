(ns ui.main.rhs
  (:require [ui.actions :as actions]
            [ui.main.input :as input]))


(defn- issues-list-item-component [*state issue]
  [:li.card
   {:class     (when (= (:id (:selected-issue @*state)) ;; TODO review on :id
                        (:id issue)) :selected)
    :on-click #(actions/select-issue! *state issue)}
   [:div
    {:class (when (:important issue) :important)}
    [:b (when (and (:selected-context @*state) 
                   (not= 0 (:search_mode (:selected-context @*state))))
          (str "(" (if (> (:short_title_ints issue) 0)
                     (:short_title_ints issue)
                     (or (:short_title issue) (:short_title_ints issue))) ") ")) 
     (:title issue)]
    [:p (:date issue)]
    [:span
     {:style {:font-size "12px"}}
     (doall 
      (->> (:contexts issue)
           (filter (fn [[idx _title]]
                     (prn idx)
                     (not= idx (:id (:selected-context @*state)))))
           (map (fn [[idx title]]
                  [:span {:key idx}
                   (str title ",")]))))]]])

(defn- issues-list-component [*state]
  [:ul.cards
   (doall (for [issue (:issues @*state)]
            ^{:key (:id issue)}
            [issues-list-item-component *state issue]))])

(defn component [_*state]
  (fn [*state]
    [:<>
     (when (= :issues (:active-search @*state))
       [input/component *state])
     [:div.scrollable
      {:class (when (= :issues (:active-search @*state)) :search-active)}
      [issues-list-component *state]]]))
