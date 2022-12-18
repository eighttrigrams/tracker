(ns main.sides.lhs
  (:require repository))

(defn- handle-event [state f]
  (fn [e]
    (f #(reset! state %) (-> e .-target .-value))))

(defn- on-click-item [state context]
  (handle-event state
                (fn [reset! _value]
                  (repository/fetch!
                   (assoc @state :selected-context context)
                   "" reset!))))

(defn- contexts-list [state]
  [:ul
   (doall (for [context (:contexts @state)]
            ^{:key (:id context)}
            [:li
             {:class (when (= (:selected-context @state) context) :selected)
              :on-click (on-click-item state context)}
             (:title context)]))])

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
