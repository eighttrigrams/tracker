(ns main.sides.lhs)

(defn- contexts-list [state]
  [:ul
   (for [context (:contexts @state)]
     ^{:key (:id context)}
     [:li
      {:on-click (fn [e]
                   (prn "yo" (:id context))
                   (swap! state
                          (fn [old-state]
                            (assoc old-state :selected-context-id 
                                   (:id context)))))}
      (:title context)])])

(defn component [_state]
  (fn [state]
    [:<>
     #_(when (= :issues (:active-search @state))
       [input-component state])
     [contexts-list state]]))
