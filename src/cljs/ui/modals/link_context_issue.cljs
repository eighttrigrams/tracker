(ns ui.modals.link-context-issue
  (:require [clojure.string :as str]
            [cljs.core.async :refer [go]]
            [cljs.core.async.interop :refer-macros [<p!]]
            [reagent.core :as r]
            api))

(defn- get-component-el []
  (.getElementById js/document "link-context-issue-component"))

(def contexts-ids (atom #{}))

(defn- reset-contexts-ids! [issue selectable-contexts]
  (reset! contexts-ids
          (doall
           (->>
            selectable-contexts
            (filter (fn [[idx _title]]
                      (contains? (set (keys (:contexts issue))) idx)))
            (map (fn [[idx _title]] idx))
            (into #{})))))

(defn component [selected-context issue]
  (let [dropdown-contexts   (r/atom '())
        selectable-contexts (r/atom (merge (conj (:secondary_contexts selected-context)
                                                 [(:id selected-context) (:title selected-context)])
                                           (:contexts issue)))]
    (reset-contexts-ids! issue @selectable-contexts)
    
    (r/create-class
     {:component-did-mount #(.focus (get-component-el))
      :reagent-render      ;
      (fn [_selected-context issue]
        [:<>
         [:div#link-context-issue-component
          {:tabIndex 0}
          (doall
           (map 
            (fn [[idx title]]
              [:div
               {:key idx}
               title
               [:input
                {:key            idx
                 :on-change      #(swap! contexts-ids 
                                         (fn [vals] ((if (contains? vals idx) disj conj) 
                                                     vals idx)))
                 :type           :checkbox
                 :defaultChecked (contains? (set (keys (:contexts issue))) idx)}]])
            @selectable-contexts))]
         
         [:hr]
         ;; TODO extract and re-use this section (context_edit, issue_edit)
         [:input#in
          {:on-change (fn [%] (go (-> (api/get-contexts (-> % .-target .-value))
                                      <p!
                                      (#(reset! dropdown-contexts %)))))}]
         [:select#sel
          (doall (map (fn [{:keys [id title]}]
                        [:option {:value (str id ":::" title)
                                  :key   id} title])
                      @dropdown-contexts))]
         [:input
          {:type     :button
           :value    "Add"
           :on-click (fn [_evt] (let [[id title]
                                      (str/split (.-value (.getElementById js/document "sel"))
                                                 #":::")]
                                  
                                  (swap! selectable-contexts assoc (int id) title)))}]]
        
        )})))

(defn get-values []
  @contexts-ids)
