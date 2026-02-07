(ns et.tr.ui.components.filter-section
  (:require [et.tr.ui.state :as state]
            [et.tr.ui.state.tasks-page :as tasks-page]
            [et.tr.i18n :refer [t]]))

(defn- handle-filter-badge-click [toggle-fn input-id item-id]
  (toggle-fn item-id)
  (js/setTimeout #(when-let [el (.getElementById js/document input-id)]
                    (.focus el)
                    (let [len (.-length (.-value el))]
                      (.setSelectionRange el len len))) 0))

(defn category-filter-section
  [{:keys [title shortcut-number filter-key items marked-ids toggle-fn clear-fn collapsed?
           toggle-collapsed-fn set-search-fn search-state-path
           section-class item-active-class label-class page-prefix]}]
  (let [marked-items (filter #(contains? marked-ids (:id %)) items)
        search-term (get-in @state/*app-state search-state-path "")
        visible-items (if (seq search-term)
                        (filter #(tasks-page/prefix-matches? (:name %) search-term) items)
                        items)
        input-id (str (or page-prefix "tasks") "-filter-" (name filter-key))
        handle-key-down (fn [e]
                          (cond
                            (= (.-key e) "Escape")
                            (do
                              (when (.-altKey e)
                                (.stopPropagation e)
                                (clear-fn)
                                (set-search-fn filter-key ""))
                              (toggle-collapsed-fn filter-key)
                              (state/focus-tasks-search))

                            (= (.-key e) "Enter")
                            (when-let [first-item (first visible-items)]
                              (.preventDefault e)
                              (toggle-fn (:id first-item)))))]
    [:div.filter-section {:class section-class}
     [:div.filter-header
      [:button.collapse-toggle
       {:on-click #(toggle-collapsed-fn filter-key)}
       (if collapsed? ">" "v")]
      [:span.filter-title (when shortcut-number {:title (str "Press Option+" shortcut-number " to toggle")})
       (if shortcut-number (str shortcut-number " " title) title)]
      (when (seq marked-ids)
        [:button.clear-filter {:on-click clear-fn} "x"])]
     (if collapsed?
       (when (seq marked-items)
         [:div.filter-items.collapsed
          (doall
           (for [item marked-items]
             ^{:key (:id item)}
             [:span.filter-item-label {:class label-class}
              (:name item)
              [:button.remove-item {:on-click #(toggle-fn (:id item))} "x"]]))])
       [:div.filter-items
        [:input.category-search
         {:id input-id
          :type "text"
          :placeholder (t :category/search)
          :value search-term
          :on-change #(set-search-fn filter-key (-> % .-target .-value))
          :on-key-down handle-key-down}]
        (doall
         (for [item visible-items]
           ^{:key (:id item)}
           [:button.filter-item
            {:class (when (contains? marked-ids (:id item)) item-active-class)
             :on-click #(handle-filter-badge-click toggle-fn input-id (:id item))}
            (:name item)]))])]))
