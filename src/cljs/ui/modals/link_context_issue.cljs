(ns ui.modals.link-context-issue
  (:require [clojure.string :as str]
            [cljs.core.async :refer [go]]
            [cljs.core.async.interop :refer-macros [<p!]]
            [reagent.core :as r]
            api))

(defn- get-component-el []
  (.getElementById js/document "link-context-issue-component"))

(def *contexts-ids (atom #{}))

(defn- reset-contexts-ids! [issue selectable-contexts]
  (reset! *contexts-ids
          (doall
           (->>
            selectable-contexts
            (filter (fn [[idx _title]]
                      (contains? (set (keys (:contexts issue))) idx)))
            (map (fn [[idx _title]] idx))
            (into #{})))))

(defn- context-adder-component [*dropdown-contexts *selectable-contexts]
  [:<>
   [:input#in
    {:on-change (fn [%] (go (-> (api/get-contexts (-> % .-target .-value))
                                <p!
                                (#(reset! *dropdown-contexts 
                                          (remove (fn [context*]
                                                    (contains? (into #{} (keys @*selectable-contexts))
                                                               (:id context*))
                                                    ) %))))))}]
   [:select#sel
    (doall (map (fn [{:keys [id title]}]
                  [:option {:value (str id ":::" title)
                            :key   id} title])
                @*dropdown-contexts))]
   [:input
    {:type     :button
     :value    "Add"
     :on-click #(let [value (.-value (.getElementById js/document "sel"))]
                  (when (not= "" value)
                    (let [[id title] (str/split value #":::")]
                      (swap! *selectable-contexts assoc (int id) title)
                      (swap! *contexts-ids conj (int id))
                      (reset! *dropdown-contexts '()))))}]])

(defn component [selected-context issue]
  (let [*dropdown-contexts    (r/atom '())
        contexts              (into {} (conj (:secondary_contexts selected-context)
                                             (when selected-context 
                                               [(:id selected-context) 
                                                (:title selected-context)])))
        *selectable-contexts  (r/atom (merge contexts (:contexts issue)))
        toggle-select-context (fn [idx] 
                                #(swap! *contexts-ids
                                        (fn [vals] ((if (contains? vals idx) disj conj)
                                                    vals idx))))]
    (reset-contexts-ids! issue @*selectable-contexts)
    
    (r/create-class
     {:component-did-mount #(.focus (get-component-el))
      :reagent-render      ;
      (fn [_selected-context _issue]
        [:<>
         [:div#link-context-issue-component
          {:tabIndex 0}
          (map 
           (fn [[idx title]]
             [:div
              {:key idx}
              title
              [:input
               {:key            idx
                :on-change      (toggle-select-context idx)
                :type           :checkbox
                :defaultChecked (contains? @*contexts-ids idx)}]])
           @*selectable-contexts)]
         
         [:hr]
         [context-adder-component *dropdown-contexts *selectable-contexts]])})))

(defn get-values []
  @*contexts-ids)
