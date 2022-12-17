(ns browser
  (:require [reagent.core :as r]
            ["react-dom/client" :refer [createRoot]]
            [goog.dom :as gdom]
            api
            ui))

(defonce root (createRoot (gdom/getElement "app")))

(defn init
  []
  (.render root (r/as-element [:div#ui-component [ui/component]])))

;; start is called by init and after code reloading finishes
(defn ^:dev/after-load start []
  (init))

;; this is called before any code is reloaded
(defn ^:dev/before-load stop []
  (js/console.log "stop"))
