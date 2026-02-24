(ns et.tr.ui.state.relations
  (:require [reagent.core :as r]
            [et.tr.ui.api :as api]))

(defonce *relations-state (r/atom {:relation-mode false
                                   :relation-source nil}))

(defn toggle-relation-mode []
  (swap! *relations-state update :relation-mode not)
  (when-not (:relation-mode @*relations-state)
    (swap! *relations-state assoc :relation-source nil)))

(defn abort-relation-mode []
  (swap! *relations-state assoc :relation-mode false :relation-source nil))

(defn set-relation-source [item-type item-id item-title]
  (swap! *relations-state assoc :relation-source {:type item-type :id item-id :title item-title}))

(defn clear-relation-source []
  (swap! *relations-state assoc :relation-source nil))

(defn add-relation [auth-headers source-type source-id target-type target-id on-success]
  (api/post-json "/api/relations"
    {:source-type source-type
     :source-id source-id
     :target-type target-type
     :target-id target-id}
    (auth-headers)
    (fn [_]
      (abort-relation-mode)
      (when on-success (on-success)))
    (fn [_] nil)))

(defn fetch-relations-for-item [auth-headers item-type item-id on-success]
  (api/fetch-json (str "/api/relations/" item-type "/" item-id)
    (auth-headers)
    on-success))

(defn delete-relation [auth-headers source-type source-id target-type target-id on-success]
  (api/delete-json "/api/relations"
    {:source-type source-type
     :source-id source-id
     :target-type target-type
     :target-id target-id}
    (auth-headers)
    (fn [_] (when on-success (on-success)))
    (fn [_] nil)))
