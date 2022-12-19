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
  (prn "now fetch" (select-keys state [:selected-context-id]))
  (go (-> {:q                   q    ;; TODO simplify: pass through the state (minus unecessary big parts)
           :selected-context-id (:id (:selected-context state))} ;; TODO pass selected-context instead only id
          (assoc :active-search (:active-search state))
          #_{:clj-kondo/ignore [:unresolved-var]}
          api/list-resources
          <p!
          (update-state state)
          f)))

#_{:clj-kondo/ignore [:unresolved-var]}
(defn save-description! [type item-id value f]
  (let [save-fn (if (= type :issue)
                  api/save-issue
                  api/save-context)]
    (go (-> item-id
            #_(assoc :active-search (:active-search state)) 
            (save-fn value)
            <p!
            #_(update-state state) ;; TODO review
            f))))

#_{:clj-kondo/ignore [:unresolved-var]}
(defn new-issue! [value context-id f]
  (go (-> 
          #_(assoc :active-search (:active-search state))
          (api/new-issue value context-id)
          <p!
          #_(update-state state) ;; TODO review
          f)))
