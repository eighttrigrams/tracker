(ns browser
  (:require [reagent.core :as r]
            ["react-dom/client" :refer [createRoot]]
            [goog.dom :as gdom]
            api
            [main.actions :as actions]
            main))

(defonce root (createRoot (gdom/getElement "app")))

(defn ui-component [*keys-pressed]
  (r/create-class {:component-did-mount actions/re-focus
                   :render (fn []
                             [:div#ui
                              [:div#main-layer
                               {;; TODO document recipe
                                ;; to make the div able to listen to key events, https://stackoverflow.com/a/3149416
                                :tabIndex 0
                                :on-key-up
                                (fn [_e]
                                  (reset! *keys-pressed {}))
                                :on-key-down
                                (fn [e]
                                  (reset! *keys-pressed 
                                          {:code          (.-code e)
                                           :ctrl-pressed? (.-ctrlKey e)}))}
                               [main/component *keys-pressed]]
                              [:div#modals-layer]])}))

(defn container []
  (let [keys-pressed 
        (r/atom {:code          nil
                 :ctrl-pressed? false})]
    [ui-component keys-pressed]))

(defn init
  []
  (.render root (r/as-element [container])))

;; start is called by init and after code reloading finishes
(defn ^:dev/after-load start []
  (init))

;; this is called before any code is reloaded
(defn ^:dev/before-load stop []
  (js/console.log "stop"))
