(ns et.tr.ui.components.category-selector
  (:require [reagent.core :as r]
            [et.tr.ui.state.tasks-page :as tasks-page]
            [et.tr.ui.keys :as keys]
            [et.tr.filters :as filters]
            [et.tr.i18n :refer [t]]))

(defn- scroll-cursor-into-view!
  "Keep the entry under the cursor on screen. This panel is 200px tall with its
  own scrollbar — unlike the sidebar's picker, which scrolls with the whole
  sidebar — so a cursor moved past the fold would otherwise be invisible, which
  is exactly when it is being used.

  A document-wide query is safe here for a checked reason: `open-selector-state`
  holds one selector id, so only one of these panels is ever open."
  []
  (js/setTimeout
   #(when-let [el (.querySelector js/document ".category-selector-items .category-selector-item.preselected")]
      (.scrollIntoView el #js {:block "nearest"}))
   0))

(defn category-selector
  [{:keys [entity entity-id-key category-type]}]
  (let [selector-id (str (get entity entity-id-key) "-" category-type)
        input-id (str "category-selector-input-" selector-id)
        sort-by-modified (fn [items] (sort-by :modified_at #(compare %2 %1) items))
        ;; Form-2 so the cursor survives the re-renders that typing in the
        ;; search box causes, and is reset whenever the panel closes — so every
        ;; open starts with the cursor nowhere. Same shape as the sidebar's.
        preselect-idx (r/atom nil)]
    (fn [{:keys [category-type entities label current-categories
                 on-categorize on-uncategorize on-close-focus-fn
                 open-selector-state search-state
                 open-selector-fn close-selector-fn set-search-fn]}]
      (let [category-ids (set (map :id current-categories))
            is-open (= open-selector-state selector-id)
            available-entities (sort-by-modified (remove #(contains? category-ids (:id %)) entities))
            filtered-entities (if (and is-open (seq search-state))
                                (filter #(tasks-page/prefix-matches? (str (:name %) " " (:tags %) " " (:badge_title %)) search-state) available-entities)
                                available-entities)
            do-close (fn []
                       (reset! preselect-idx nil)
                       (close-selector-fn)
                       (when on-close-focus-fn (on-close-focus-fn)))
            ;; Only for the ring below. What Enter takes is read live, inside
            ;; the handler — a binding from here would be the cursor as it stood
            ;; at the last render, and a press that lands before reagent has
            ;; flushed the move would act on the previous position. That is not
            ;; hypothetical: computing it here first cost two e2e scenarios,
            ;; which took entry 1 while the ring was on entry 2.
            preselect @preselect-idx
            entry-to-take (fn []
                            (let [idx @preselect-idx]
                              (if (and idx (< idx (count filtered-entities)))
                                (nth filtered-entities idx)
                                (first filtered-entities))))]
        (when-not is-open
          (reset! preselect-idx nil))
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
               :auto-complete "off"
               :placeholder (t :category/search)
               :value search-state
               :auto-focus true
               :on-change #(set-search-fn (-> % .-target .-value))
               :on-key-down (fn [e]
                              (if-let [direction (keys/cursor-key e)]
                                (do
                                  (.preventDefault e)
                                  (swap! preselect-idx keys/move-cursor direction (count filtered-entities))
                                  (scroll-cursor-into-view!))
                                (case (.-key e)
                                  "Escape" (do-close)
                                  ;; Whatever the cursor is on, or the first of
                                  ;; however many are listed — not only a list of
                                  ;; one, as this used to insist. The panel is
                                  ;; sorted most-recent-first, so what Enter
                                  ;; takes is always the button you are looking
                                  ;; at. Same rule the sidebar's picker has had
                                  ;; all along; this was the odd one out.
                                  "Enter" (when-let [ent (entry-to-take)]
                                            (.preventDefault e)
                                            (on-categorize (:id ent))
                                            (do-close))
                                  nil)))}]
             [:div.category-selector-items
              (if (seq filtered-entities)
                (doall
                 (map-indexed
                  (fn [idx ent]
                    ^{:key (:id ent)}
                    [:button.category-selector-item
                     {:class (str category-type (when (= idx preselect) " preselected"))
                      :on-click (fn [e]
                                  (.stopPropagation e)
                                  (on-categorize (:id ent))
                                  (do-close))}
                     (:name ent)])
                  filtered-entities))
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
