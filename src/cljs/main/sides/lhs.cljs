(ns main.sides.lhs)

(defn- contexts-list [state]
  [:ul
   (for [context (:contexts @state)]
     ^{:key (:id context)}
     [:li
      {:class (when (= (:selected-context @state) context) :selected)
       :on-click (fn [e]
                   (prn "yo" (:id context))
                   (swap! state
                          (fn [old-state]
                            (assoc old-state :selected-context
                                   context))))}
      (:title context)])])

(defn component [_state]
  (fn [state]
    (if (:selected-issue @state)
      [:<> 
       [:h1 (:title (:selected-issue @state))]
       [:p (:description (:selected-issue @state))]]
      [:<>
       #_(when (= :issues (:active-search @state))
           [input-component state])
       [contexts-list state]])))
