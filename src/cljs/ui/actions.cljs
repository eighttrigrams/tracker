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