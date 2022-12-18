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