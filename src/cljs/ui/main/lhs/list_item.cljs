(ns ui.main.lhs.list-item
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

(defn component [*state context]
  [:li
   {:class    (when (= (:selected-context @*state) context) :selected)
    :on-click (on-click-item *state context)}
   (:title context)])
