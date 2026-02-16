(ns et.tr.ui.components.category-selector
  (:require [et.tr.ui.state.tasks-page :as tasks-page]
            [et.tr.filters :as filters]
            [et.tr.i18n :refer [t]]))

(defn category-selector
  [{:keys [entity entity-id-key category-type]}]
  (let [selector-id (str (get entity entity-id-key) "-" category-type)
        input-id (str "category-selector-input-" selector-id)]
    (fn [{:keys [category-type entities label current-categories
                 on-categorize on-uncategorize on-close-focus-fn
                 open-selector-state search-state
                 open-selector-fn close-selector-fn set-search-fn]}]
      (let [category-ids (set (map :id current-categories))
            is-open (= open-selector-state selector-id)
            available-entities (remove #(contains? category-ids (:id %)) entities)
            filtered-entities (if (and is-open (seq search-state))
                                (filter #(tasks-page/prefix-matches? (str (:name %) " " (:badge_title %)) search-state) available-entities)
                                available-entities)
            do-close (fn []
                       (close-selector-fn)
                       (when on-close-focus-fn (on-close-focus-fn)))]
        [:div.tag-selector
         [:div.category-selector-dropdown
          [:button.category-selector-trigger
           {:class (str category-type (when is-open " open"))
            :on-click (fn [e]
                        (.stopPropagation e)
                        (if is-open
                          (do-close)
                          (do
                            (open-selector-fn selector-id)
                            (js/setTimeout #(when-let [el (.getElementById js/document input-id)]
                                              (.focus el)) 0))))}
           (str "+ " label)]
          (when is-open
            [:div.category-selector-panel
             {:on-click #(.stopPropagation %)}
             [:input.category-selector-search
              {:id input-id
               :type "text"
               :placeholder (t :category/search)
               :value search-state
               :auto-focus true
               :on-change #(set-search-fn (-> % .-target .-value))
               :on-key-down (fn [e]
                              (case (.-key e)
                                "Escape" (do-close)
                                "Enter" (when (= 1 (count filtered-entities))
                                          (on-categorize (:id (first filtered-entities)))
                                          (do-close))
                                nil))}]
             [:div.category-selector-items
              (if (seq filtered-entities)
                (doall
                 (for [ent filtered-entities]
                   ^{:key (:id ent)}
                   [:button.category-selector-item
                    {:class category-type
                     :on-click (fn [e]
                                 (.stopPropagation e)
                                 (on-categorize (:id ent))
                                 (do-close))}
                    (:name ent)]))
                [:div.category-selector-empty (t :category/no-results)])]])]
         (doall
          (for [category current-categories]
            ^{:key (str category-type "-" (:id category))}
            [:span.tag
             {:class category-type}
             (filters/badge-label category)
             [:button.remove-tag
              {:on-click #(on-uncategorize (:id category))}
              "x"]]))]))))
