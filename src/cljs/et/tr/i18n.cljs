(ns et.tr.i18n
  (:require [et.tr.ui.api :as api]
            [reagent.core :as r]
            [clojure.string :as str]))

(defonce translations (r/atom nil))
(defonce current-language (r/atom "en"))

(defn load-translations! [on-success]
  ;; No auth headers: translations are public, and this runs before there is a
  ;; user to have any.
  (api/fetch-json-with-error "/api/translations" nil
    (fn [data]
      (reset! translations data)
      (when on-success (on-success)))
    (fn [_]
      (js/console.error "Failed to load translations"))))

(defn set-language! [lang]
  (reset! current-language (name lang)))

(defn t
  ([key] (t key nil))
  ([key args]
   (let [lang (keyword @current-language)
         text (or (get-in @translations [lang key])
                  (get-in @translations [:en key])
                  (name key))]
     (if args
       (reduce-kv (fn [s k v]
                    (str/replace s (str "%" (name k)) (str v)))
                  text
                  args)
       text))))

(defn tf [key & args]
  (let [lang (keyword @current-language)
        text (or (get-in @translations [lang key])
                 (get-in @translations [:en key])
                 (name key))]
    (reduce (fn [s arg]
              (str/replace-first s "%s" (str arg)))
            text
            args)))
