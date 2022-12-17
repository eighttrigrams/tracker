(ns main.sides.rhs)

(defn component [state]
  [:ul
   (map (fn [%] [:li {:key (:id %)} (:title %)])
        (:issues @state))])
