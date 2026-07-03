(ns et.tr.ui.components.filter-section
  (:require [reagent.core :as r]
            [et.tr.ui.state :as state]
            [et.tr.ui.state.tasks-page :as tasks-page]
            [et.tr.i18n :refer [t]]))

(defn- sort-by-modified-desc [items]
  (->> items (sort-by :modified_at #(compare %2 %1)) vec))

(defn category-badge-toggle []
  (let [showing? (state/show-collapsed-categories?)]
    [:button.category-badge-toggle
     {:class (when-not showing? "struck")
      :title (if showing?
               "Hide category badges on collapsed cards"
               "Show category badges on collapsed cards")
      :on-click #(state/toggle-show-collapsed-categories)}
     [:span.eye-icon
      [:svg {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
             :stroke-width "2" :stroke-linecap "round" :stroke-linejoin "round"
             :width "20" :height "20"}
       [:path {:d "M1 12s4-7 11-7 11 7 11 7-4 7-11 7-11-7-11-7z"}]
       [:circle {:cx "12" :cy "12" :r "3"}]]]]))

(def ^:private filter-key->entity-type
  {:people   :category-person
   :places   :category-place
   :projects :category-project
   :goals    :category-goal})

(defn category-filter-section
  "Sidebar category picker. Form-2 so the keyboard pre-selection index survives
  re-renders while the picker stays open; it is reset to nil (no pre-selection)
  whenever the picker is closed (collapsed?) or an item is selected, so every
  open starts fresh."
  [_]
  (let [preselect-idx (r/atom nil)]
    (fn [{:keys [title shortcut-number filter-key items marked-ids toggle-fn clear-fn collapsed?
                 toggle-collapsed-fn set-search-fn search-state-path
                 section-class item-active-class label-class page-prefix]}]
      (when collapsed?
        (reset! preselect-idx nil))
      (let [marked-items (filter #(contains? marked-ids (:id %)) items)
            search-term (get-in @state/*app-state search-state-path "")
            visible-items (sort-by-modified-desc
                           (if (seq search-term)
                             (filter #(tasks-page/prefix-matches? (str (:name %) " " (:tags %) " " (:badge_title %)) search-term) items)
                             items))
            input-id (str (or page-prefix "tasks") "-filter-" (name filter-key))
            preselect @preselect-idx
            select-item! (fn [item]
                           ;; single chokepoint for all select paths (mouse, Enter,
                           ;; arrow+Enter): bump modified_at only when ADDING.
                           (when-not (contains? marked-ids (:id item))
                             (state/bump-category-modified filter-key item))
                           (reset! preselect-idx nil)
                           (toggle-fn (:id item)))
            handle-badge-click (fn [item]
                                 (select-item! item)
                                 (js/setTimeout #(when-let [el (.getElementById js/document input-id)]
                                                   (.focus el)
                                                   (let [len (.-length (.-value el))]
                                                     (.setSelectionRange el len len))) 0))
            handle-key-down (fn [e]
                              (cond
                                (= (.-key e) "Escape")
                                (do
                                  (reset! preselect-idx nil)
                                  (when (.-altKey e)
                                    (.stopPropagation e)
                                    (clear-fn)
                                    (set-search-fn filter-key ""))
                                  (toggle-collapsed-fn filter-key)
                                  (js/setTimeout
                                   #(when-let [el (.getElementById js/document (str (or page-prefix "tasks") "-filter-search"))]
                                      (.focus el)) 0))

                                (= (.-key e) "ArrowDown")
                                (do
                                  (.preventDefault e)
                                  (let [n (count visible-items)]
                                    (when (pos? n)
                                      (swap! preselect-idx #(if (nil? %) 0 (min (inc %) (dec n)))))))

                                (= (.-key e) "ArrowUp")
                                (do
                                  (.preventDefault e)
                                  (let [n (count visible-items)]
                                    (when (pos? n)
                                      (swap! preselect-idx #(if (nil? %) 0 (max (dec %) 0))))))

                                (= (.-key e) "Enter")
                                (let [idx @preselect-idx
                                      item (if (and idx (< idx (count visible-items)))
                                             (nth visible-items idx)
                                             (first visible-items))]
                                  (when item
                                    (.preventDefault e)
                                    (select-item! item)))))]
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
             (let [entity-type (filter-key->entity-type filter-key)]
               [:div.filter-items.collapsed
                (doall
                 (for [item marked-items]
                   ^{:key (:id item)}
                   [:span.filter-item-label {:class label-class}
                    (if entity-type
                      [:span.filter-item-name
                       {:on-click #(state/open-edit-modal entity-type item)}
                       (:name item)]
                      (:name item))
                    [:button.remove-item {:on-click #(toggle-fn (:id item))} "x"]]))]))
           [:div.filter-items
            [:input.category-search
             {:id input-id
              :type "text"
              :auto-complete "off"
              :placeholder (t :category/search)
              :value search-term
              :on-change #(set-search-fn filter-key (-> % .-target .-value))
              :on-key-down handle-key-down}]
            (doall
             (map-indexed
              (fn [idx item]
                ^{:key (:id item)}
                [:button.filter-item
                 {:class (str (when (contains? marked-ids (:id item)) item-active-class)
                              (when (= idx preselect) " preselected"))
                  :on-click #(handle-badge-click item)}
                 (:name item)])
              visible-items))])]))))
