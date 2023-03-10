(ns ui.modals.context-edit
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [net.eighttrigrams.cljs-text-editor.editor :as editor]
            api
            [cljs.core.async :refer [go]]
            [cljs.core.async.interop :refer-macros [<p!]]))

(defn- get-title-el []
  (.getElementById js/document "context-title"))

(defn- get-short-title-el []
  (.getElementById js/document "context-short-title"))

(defn- get-tags-el []
  (.getElementById js/document "context-tags")) ;; TODO maybe just name it "tags"

(def *secondary-contexts (r/atom {}))

(defn component [context]
  (let [*dropdown-contexts (r/atom '())]
    (reset! *secondary-contexts (:secondary_contexts context))
    (r/create-class 
     {:component-did-mount #(do (.focus (get-title-el))
                                (editor/create (get-title-el) {:input-field-mode? true})
                                (editor/create (get-short-title-el) {:input-field-mode? true})
                                (editor/create (get-tags-el) {:input-field-mode? true}))
      :reagent-render
      (fn [context]
        [:<> 
         [:div
          [:input#context-title.line
           {:autoComplete :off
            :defaultValue (:title context)}]]
         [:div
          [:input#context-short-title.line
           {:autoComplete :off
            :defaultValue (:short_title context)}]] ;; TODO work with short-title
         [:div
          [:input#context-tags.line
           {:autoComplete :off
            :defaultValue (:tags context)}]]
         [:ul (doall (map (fn [[idx title]]
                            [:li
                             {:key idx
                              :on-click #(swap! *secondary-contexts dissoc idx)}
                             title]) @*secondary-contexts))]
         [:input#in
          {:on-change (fn [%] (go (-> (api/get-contexts (-> % .-target .-value))
                                      <p!
                                      (#(reset! *dropdown-contexts 
                                                (remove (fn [context*]
                                                          (or 
                                                           (contains? (into #{} 
                                                                            (keys @*secondary-contexts))
                                                                      (:id context*))
                                                           (= (:id context)
                                                                 (:id context*)))) %))))))}]
         [:select#sel
          (doall (map (fn [{:keys [id title]}]
                        [:option {:value (str id ":::" title)
                                  :key id} title])
                      @*dropdown-contexts))]
         [:input
          {:type :button
           :value "Add"
           :on-click #(let [value (.-value (.getElementById js/document "sel"))]
                        (when (not= "" value)
                          (let [[id title] (str/split value #":::")]
                            (swap! *secondary-contexts assoc (int id) title)
                            (reset! *dropdown-contexts '()))))}]])})))

(defn get-values [id]
  {:context
   {:id          id
    :title       (.-value (get-title-el))
    :short_title (.-value (get-short-title-el))
    :tags        (.-value (get-tags-el))}
   :secondary-contexts-ids (keys @*secondary-contexts)})
