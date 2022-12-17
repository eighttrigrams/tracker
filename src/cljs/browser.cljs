(ns browser
  (:require [ajax.core :refer [POST]]
            [reagent.core :as r]
            ["react-dom/client" :refer [createRoot]]
            [goog.dom :as gdom]
            [cljs.core.async :refer [go]]
            api
            [cljs.core.async.interop :refer-macros [<p!]]))

(defonce root (createRoot (gdom/getElement "app")))

(defn fetch []
  (go (->> ""
            #_{:clj-kondo/ignore [:unresolved-var]}
            api/list-resources 
            <p! 
            prn)))

(defn simple-component []
  [:div
   [:p "I am a component"]
   [:p.someclass
    "I have " [:strong "bold"]
    [:span {:style {:color "red"}} " and red!!! "] "text."]])

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
