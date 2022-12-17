(ns main
  (:require [reagent.core :as r]
            [cljs.core.async :refer [go]]
            api
            [main.sides :as sides]
            [cljs.core.async.interop :refer-macros [<p!]]))

(def original-state {:issues []})

(defn swap-state [state issues]
  (swap! state (fn [old-state] (assoc old-state :issues issues))))

(defn fetch! [state]
  (go (->> ""
           #_{:clj-kondo/ignore [:unresolved-var]}
           api/list-resources
           <p!
           (swap-state state))))

(defn component [_keys-pressed]
  (let [state (r/atom original-state)]
    (fn [keys-pressed]
      (prn "keys-pressed" @keys-pressed)
      (fetch! state)
      [:div#sides-component
       [sides/component state]])))
