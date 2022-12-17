(ns browser
  (:require [reagent.core :as r]
            ["react-dom/client" :refer [createRoot]]
            [goog.dom :as gdom]
            [cljs.core.async :refer [go]]
            api
            [cljs.core.async.interop :refer-macros [<p!]]))

(defonce root (createRoot (gdom/getElement "app")))

(def state (r/atom {:issues []}))

(defn swap-state [%]
  (swap! state (fn [state] (assoc state :issues %))))

(defn fetch []
  (go (->> ""
            #_{:clj-kondo/ignore [:unresolved-var]}
            api/list-resources 
            <p! 
            swap-state)))

(defn simple-component []
  (fn []
    [:ul
     (doall (map (fn [%] [:li {:key (:id %)} (:title %)])
                     (:issues @state)))]))

(defn init
  []
  (fetch)
  (.render root (r/as-element [simple-component])))

;; start is called by init and after code reloading finishes
(defn ^:dev/after-load start []
  (init))

;; this is called before any code is reloaded
(defn ^:dev/before-load stop []
  (js/console.log "stop"))
