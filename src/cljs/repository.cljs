(ns repository
  (:require api
            [cljs.core.async :refer [go]]
            [cljs.core.async.interop :refer-macros [<p!]]))

(defn swap-state [state issues]
  (swap! state (fn [old-state] (assoc old-state :issues issues))))

(defn fetch! [state q]
  (go (->> q
           #_{:clj-kondo/ignore [:unresolved-var]}
           api/list-resources
           <p!
           (swap-state state))))