(ns main.sides.rhs
  (:require repository))

(defn component [_state]
  (fn [state]
    [:<>
     (when (:issues-search-active? @state)
       [:input 
        {:on-change (fn [e]
                      #_(swap! state (fn [old-state] (assoc old-state :q (.-value (.-target e)))))
                      (repository/fetch! state (.-value (.-target e))))}])
     [:ul
      (map (fn [%] [:li {:key (:id %)} (:title %)])
           (:issues @state))]]))
