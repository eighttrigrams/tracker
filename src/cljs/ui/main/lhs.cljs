(ns ui.main.lhs
  (:require repository
            [ui.main.input :as input]
            [ui.main.lhs.context-detail :as context-detail]
            [ui.main.lhs.list-item :as list-item]))

(defn- contexts-list [*state]
  [:ul
   (doall 
    (for [context (:contexts @*state)]
      ^{:key (:id context)}
      [list-item/component *state context]))])

(defn- issue-detail-component [*state]
  [:<>
   [:h1 (:title (:selected-issue @*state))]
   [:p (:description (:selected-issue @*state))]])

(defn component [_*state]
  (fn [*state]
    (cond
      (= :contexts (:active-search @*state))
      [:<>
       [input/component *state]
       [:div.list-component
        {:class :search-active}
        [contexts-list *state]]]
      (:selected-issue @*state)
      [issue-detail-component *state]
      (:selected-context @*state)
      [context-detail/component *state]
      :else
      [:div.list-component
       [contexts-list *state]])))
