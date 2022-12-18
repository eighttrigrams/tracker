(ns repository
  (:require api
            [cljs.core.async :refer [go]]
            [cljs.core.async.interop :refer-macros [<p!]]))

(defn- update-state [{:keys [issues contexts]} state]
  (-> 
   state
   (assoc :issues (or issues (:issues state)))
   (assoc :contexts (or contexts (:contexts state)))))

(defn fetch! [state q f]
  (go (-> {:q                   q    ;; TODO simplify: pass through the state (minus unecessary big parts)
           :selected-context-id (:id (:selected-context state))}
          (assoc :active-search (:active-search state))
          #_{:clj-kondo/ignore [:unresolved-var]}
          api/list-resources
          <p!
          (update-state state)
          f)))
