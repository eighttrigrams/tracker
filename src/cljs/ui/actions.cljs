(ns ui.actions
  (:require repository))

(defn re-focus []
  (when-let [el (.getElementById js/document "main-layer")]
    (.focus el)))

(defn quit-search! [*state]
  (repository/fetch! @*state ""
                     #(reset! *state
                              (dissoc % :active-search)))
  (re-focus))

(defn deselect-context! [*state]
  (-> @*state
      (dissoc :selected-context)
      (repository/fetch! "" #(reset! *state %))))

(defn- select-item! [*state item key]
  (repository/fetch! (-> 
                      @*state 
                      (assoc key item)
                      (dissoc :active-search)) ""
                     #(reset! *state %)))

(defn select-context! [*state context]
  (select-item! *state context :selected-context))

(defn select-issue! [*state issue]
  (select-item! *state issue :selected-issue))

(defn search! [*state value]
  (repository/fetch! @*state value
                     #(reset! *state %)))

(defn cancel-modal! [*state]
  (swap! *state dissoc :modal)
  (re-focus))

(defn new-issue! [*state value]
  (repository/new-issue!
   value 
   (:id (:selected-context @*state))
   (fn [updated-item]
     (repository/fetch! @*state ""
                        #(reset! *state
                                 (-> %
                                     (assoc :selected-issue updated-item)
                                     (dissoc :modal))))
     (re-focus))))

;; TODO use promesa; dedup, see above
(defn save-description! [*state type id value]
  (repository/save-description! 
   type id value
   (fn [updated-item]
     (prn "updated-item" updated-item)
     (repository/fetch! @*state "" 
                        #(reset! *state 
                                 (-> %
                                     (assoc (if (= :issue type)
                                              :selected-issue
                                              :selected-context) updated-item)
                                     (dissoc :modal))))
     (re-focus))))