(ns ui.main.lhs
  (:require repository))

(defn- handle-event [*state f]
  (fn [e]
    (f #(reset! *state %) (-> e .-target .-value))))

(defn- on-click-item [*state context]
  (handle-event *state
                (fn [reset! _value]
                  (repository/fetch!
                   (assoc @*state :selected-context context)
                   "" reset!))))

(defn- list-item [*state context]
  [:li
   {:class    (when (= (:selected-context @*state) context) :selected)
    :on-click (on-click-item *state context)}
   (:title context)])

(defn- contexts-list [*state]
  [:ul
   (doall 
    (for [context (:contexts @*state)]
      ^{:key (:id context)}
      [list-item *state context]))])

(defn component [_*state]
  (fn [*state]
    (cond 
      (:selected-issue @*state)
      [:<> 
       [:h1 (:title (:selected-issue @*state))]
       [:p (:description (:selected-issue @*state))]]
      (:selected-context @*state)
      [:ul 
       [list-item *state (:selected-context @*state)]]
      :else
      [:<>
       #_(when (= :issues (:active-search @state))
           [input-component state])
       [contexts-list *state]])))
