(ns ui.modals.issue-edit
  (:require [clojure.string :as str]
            [reagent.core :as r]
            api
            [cljs.core.async :refer [go]]
            [cljs.core.async.interop :refer-macros [<p!]]))

(defn- get-title-el []
  (.getElementById js/document "issue-title"))

(defn- get-short-title-el []
  (.getElementById js/document "issue-short-title"))

(defn- get-tags-el []
  (.getElementById js/document "issue-tags"))

(defn- get-event-el []
  (.getElementById js/document "has-date"))

(defn- get-event-archived-el []
  (.getElementById js/document "event-archived"))

(defn- get-date-el []
  (.getElementById js/document "date-picker"))

(def *related-issues (r/atom {}))

(defn basic-elements-component [issue]
  [:<>
   [:div
    [:input#issue-title.line
     {:autoComplete :off
      :defaultValue (:title issue)}]]
   [:div
    [:input#issue-short-title.line
     {:autoComplete :off
      :defaultValue (:short_title issue)}]] ;; TODO work with short-title
   [:div
    [:input#issue-tags.line
     {:autoComplete :off
      :defaultValue (:tags issue)}]]])

(defn event-component [issue *date-visible?]
  [:<>
   [:div
    [:p "Has event?"]
    [:input#has-date
     {:type           :checkbox
      :defaultChecked @*date-visible?
      :on-click       #(swap! *date-visible? not)}]]
   (when @*date-visible?
     [:<>
      [:p "Event"]
      [:input#date-picker
       {:type         :date
        :defaultValue (:date issue)}]
      [:p "Event archived?"]
      [:input#event-archived
       {:type           :checkbox
        :defaultChecked (:event_archived? issue)}]])])

(defn component [issue]
  (let [*date-visible?  (r/atom (boolean (:date issue)))
        *dropdown-issues (r/atom '())]
    (reset! *related-issues (:related_issues issue))
    (r/create-class
     {:component-did-mount #(.focus (get-title-el))
      :reagent-render      ;
      (fn [_item]
        [:<>
         [basic-elements-component issue]
         [:hr]
         [:h4 "Event"]
         [event-component issue *date-visible?]
         [:hr]
         [:h4 "Related issues"]
         [:ul (doall (map (fn [[idx title]]
                            [:li
                             {:key idx
                              :on-click #(swap! *related-issues dissoc idx)}
                             title]) @*related-issues))]
         [:input#in
          {:on-change (fn [%] (go (-> (api/get-issues (-> % .-target .-value))
                                      <p!
                                      (#(reset! *dropdown-issues %)))))}]
         [:select#sel
          (doall (map (fn [{:keys [id title]}]
                        [:option {:value (str id ":::" title)
                                  :key id} title]) 
                      @*dropdown-issues))]
         [:input
          {:type :button
           :value "Add"
           :on-click (fn [_evt] (let [[id title]
                                      (str/split (.-value (.getElementById js/document "sel")) 
                                                 #":::")]
                                  (swap! *related-issues assoc (int id) title)))}]])})))

(defn get-values [id]
  {:issue              {:id              id
                        :title           (.-value (get-title-el))
                        :short_title     (.-value (get-short-title-el))
                        :tags            (.-value (get-tags-el))
                        :has-event?      (.-checked (get-event-el))
                        :event_archived? (when (get-event-archived-el) 
                                           (.-checked (get-event-archived-el)))
                        :date            (when (get-date-el) (.-value (get-date-el)))}
   :related-issues-ids (keys @*related-issues)})
