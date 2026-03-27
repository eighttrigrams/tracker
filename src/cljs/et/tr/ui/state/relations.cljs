(ns et.tr.ui.state.relations
  (:require [reagent.core :as r]
            [et.tr.ui.api :as api]
            [et.tr.ui.url :as url]))

(defonce *relations-state
  (r/atom {:relation-mode false
           :relation-source nil}))

(defn relation-mode-active? []
  (:relation-mode @*relations-state))

(defn relation-source []
  (:relation-source @*relations-state))

(defn toggle-relation-mode []
  (swap! *relations-state update :relation-mode not)
  (when-not (:relation-mode @*relations-state)
    (swap! *relations-state assoc :relation-source nil)))

(defn abort-relation-mode []
  (swap! *relations-state assoc
         :relation-mode false
         :relation-source nil))

(defn set-relation-source [source-type source-id]
  (swap! *relations-state assoc :relation-source {:type source-type :id source-id}))

(defn clear-relation-source []
  (swap! *relations-state assoc :relation-source nil))

(defn item-type->prefix [item-type]
  (url/type->prefix item-type))

(defn add-relation [auth-headers source-type source-id target-type target-id on-success]
  (api/post-json "/api/relations"
                 {:source-type source-type
                  :source-id source-id
                  :target-type target-type
                  :target-id target-id}
                 (auth-headers)
                 (fn [_]
                   (abort-relation-mode)
                   (when on-success (on-success)))))

(defn delete-relation [auth-headers source-type source-id target-type target-id on-success]
  (api/delete-json "/api/relations"
                   {:source-type source-type
                    :source-id source-id
                    :target-type target-type
                    :target-id target-id}
                   (auth-headers)
                   (fn [_]
                     (when on-success (on-success)))))
