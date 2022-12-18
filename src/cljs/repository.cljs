(ns repository
  (:require api
            [cljs.core.async :refer [go]]
            [cljs.core.async.interop :refer-macros [<p!]]))

(defn- swap-state [{:keys [issues contexts]} state]
  (swap! state 
         (fn [old-state]
           (-> 
            old-state
            (assoc :issues (or issues (:issues old-state)))
            (assoc :contexts (or contexts (:contexts old-state)))))))

(defn fetch! [state q]
  (go (-> {:q                   q    ;; TODO simplify: pass through the state (minus unecessary big parts)
           :selected-context-id (:selected-context-id @state)}
          (assoc :active-search (:active-search @state))
          #_{:clj-kondo/ignore [:unresolved-var]}
          api/list-resources
          <p!
          (swap-state state))))
