(ns browser
  (:require [ajax.core :refer [POST]]
            [reagent.core :as r]
            ["react-dom/client" :refer [createRoot]]
            [goog.dom :as gdom]))

(defonce root (createRoot (gdom/getElement "app")))

(defn fetch []
  (POST "/api" {:body (.stringify js/JSON (clj->js {:msg "hi"}))
                :headers {"Content-Type" "application/json"}
                :handler (fn [resp] (prn "Response from backend:" resp))
                :error-handler (fn [resp] (prn "Error response:" resp))}))

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
