(ns et.tr.ui.components.item-card
  (:require [clojure.string :as str]
            [et.tr.ui.state :as state]
            [et.tr.ui.components.task-item :as task-item]
            [et.tr.ui.components.relation-link :as relation-link]
            [et.tr.ui.components.relation-badges :as relation-badges]
            [et.tr.i18n :refer [t]]))

(defn make-inline-edit
  [{:keys [edit-id-path title-path update-fn build-args state-atom]}]
  {:edit-id-path edit-id-path
   :title-path title-path
   :update-fn update-fn
   :state-atom state-atom
   :build-args (or build-args
                   (fn [item title-value done-cb]
                     [(:id item) title-value (:description item) (:tags item) done-cb]))})

(defn- ie-atom [inline-edit]
  (or (:state-atom inline-edit) state/*app-state))

(defn- inline-editing? [inline-edit item]
  (boolean
    (and inline-edit
         (= (get @(ie-atom inline-edit) (:edit-id-path inline-edit)) (:id item)))))

(defn- scope-selector [{:keys [value on-set]}]
  (let [scope (or value "both")]
    [:div.task-scope-selector.toggle-group.compact
     (for [s ["private" "both" "work"]]
       ^{:key s}
       [:button.toggle-option
        {:class (when (= scope s) "active")
         :on-click (fn [e]
                     (.stopPropagation e)
                     (on-set s))}
        s])]))

(defn- importance-selector [{:keys [value on-set]}]
  (let [importance (or value "normal")]
    [:div.task-importance-selector.toggle-group.compact
     (for [[level label] [["normal" "○"] ["important" "★"] ["critical" "★★"]]]
       ^{:key level}
       [:button.toggle-option
        {:class (str level (when (= importance level) " active"))
         :on-click (fn [e]
                     (.stopPropagation e)
                     (on-set level))}
        label])]))

(defn- urgency-selector [{:keys [value on-set]}]
  (let [urgency (or value "default")]
    [:div.task-urgency-selector.toggle-group.compact
     (for [[level label] [["default" "—"] ["urgent" "🚨"] ["superurgent" "🚨🚨"]]]
       ^{:key level}
       [:button.toggle-option
        {:class (str level (when (= urgency level) " active"))
         :on-click (fn [e]
                     (.stopPropagation e)
                     (on-set level))}
        label])]))

(defn- delete-widget [{:keys [on-click]}]
  [:div.combined-button-wrapper
   [:button.delete-btn {:on-click on-click}
    (t :task/delete)]])

(defn- footer-widget [spec]
  (case (:type spec)
    :scope [scope-selector spec]
    :importance [importance-selector spec]
    :urgency [urgency-selector spec]
    :delete [delete-widget spec]
    :done [task-item/task-combined-action-button (:item spec)
           :extra-dropdown-items (:extra-dropdown-items spec)]
    :custom (:render spec)
    nil))

(defn- card-title-el [{:keys [item expanded? inline-edit title-expanded-click title-text-class]}]
  (let [editing? (inline-editing? inline-edit item)
        {:keys [edit-id-path title-path update-fn build-args]} inline-edit
        a (ie-atom inline-edit)
        text-class (or title-text-class "item-title-text")]
    (if editing?
      [task-item/inline-title-edit
       {:title (or (get @a title-path) "")
        :on-change #(swap! a assoc title-path %)
        :on-commit (fn []
                     (apply update-fn
                            (build-args item
                                        (or (get @a title-path) "")
                                        #(swap! a dissoc edit-id-path title-path))))
        :on-cancel #(swap! a dissoc edit-id-path title-path)}]
      [(keyword (str "span." text-class))
       (when inline-edit
         {:on-click (fn [e]
                      (cond
                        (and expanded? (.-altKey e))
                        (do (.stopPropagation e)
                            (swap! a assoc
                                   edit-id-path (:id item)
                                   title-path (:title item)))

                        (and expanded? title-expanded-click)
                        (do (.stopPropagation e)
                            (title-expanded-click item))))})
       (:title item)])))

(defn- card-title-area [{:keys [item expanded? title-class relation-link inline-edit badges title-extra title-expanded-click title-content title-text-class]}]
  (let [title-el [card-title-el {:item item
                                 :expanded? expanded?
                                 :inline-edit inline-edit
                                 :title-expanded-click title-expanded-click
                                 :title-text-class title-text-class}]]
    (if title-content
      [(keyword (str "div." title-class))
       (when relation-link
         [relation-link/relation-link-button (first relation-link) (second relation-link)])
       (title-content {:item item :expanded? expanded? :editing? (inline-editing? inline-edit item) :title-el title-el})]
      [(keyword (str "div." title-class))
       (when relation-link
         [relation-link/relation-link-button (first relation-link) (second relation-link)])
       (when (:importance? badges)
         (let [importance (:importance item)]
           (when (and importance (not= importance "normal"))
             [:span.importance-badge {:class importance}
              (case importance "important" "★" "critical" "★★" nil)])))
       (when-let [render (:render badges)]
         [render item])
       title-extra
       title-el])))

(defn- card-header [{:keys [item expanded? on-toggle inline-edit header-class title-class
                            relation-link badges title-extra title-expanded-click title-content title-text-class
                            toolbar date date-class header-extra]}]
  (let [editing? (inline-editing? inline-edit item)]
    [(keyword (str "div." header-class))
     {:on-click (fn [_]
                  (when-not (or editing?
                                (and expanded? (not (.. js/window getSelection -isCollapsed))))
                    (on-toggle)))}
     [card-title-area {:item item
                       :expanded? expanded?
                       :title-class title-class
                       :relation-link relation-link
                       :inline-edit inline-edit
                       :badges badges
                       :title-extra title-extra
                       :title-expanded-click title-expanded-click
                       :title-content title-content
                       :title-text-class title-text-class}]
     (when (and expanded? toolbar)
       [:div.item-toolbar
        (when-let [cal (:calendar toolbar)]
          [:button.calendar-icon
           {:on-click (fn [e]
                        (.stopPropagation e)
                        ((:on-click cal)))}
           "📅"])])
     (when date
       [(keyword (str "div." date-class))
        ((:render date) item)])
     header-extra]))

(defn- card-categories [{:keys [item selector-fn relations-prefix]}]
  (let [{:keys [people places projects goals]} @state/*app-state]
    [:div.item-tags
     [selector-fn item state/CATEGORY-TYPE-PERSON people (t :category/person)]
     [selector-fn item state/CATEGORY-TYPE-PLACE places (t :category/place)]
     [selector-fn item state/CATEGORY-TYPE-PROJECT projects (t :category/project)]
     [selector-fn item state/CATEGORY-TYPE-GOAL goals (t :category/goal)]
     (when relations-prefix
       [relation-badges/relation-badges-expanded (:relations item) relations-prefix (:id item)])]))

(defn- card-description [{:keys [item edit-type content-type on-edit loaded-fn]}]
  (let [loaded? (if loaded-fn (loaded-fn item) true)
        open-edit (fn [] (if on-edit (on-edit item) (state/set-editing-modal edit-type item)))]
    (cond
      (and loaded? (seq (:description item)))
      [task-item/clampable-description
       {:text (:description item)
        :content-type content-type
        :on-click open-edit}]

      loaded?
      [:button.edit-icon.description-placeholder
       {:on-click (fn [e]
                    (.stopPropagation e)
                    (open-edit))}
       "✎"]

      :else
      [:button.edit-icon.description-placeholder.description-loading
       {:on-click (fn [e]
                    (.stopPropagation e)
                    (open-edit))}
       "…"])))

(defn- card-expanded-body [{:keys [item content-class description categories footer expanded-prefix expanded-suffix]}]
  [(keyword (str "div." content-class))
   expanded-prefix
   (when description
     [card-description {:item item
                        :edit-type (:edit-type description)
                        :content-type (:content-type description)
                        :on-edit (:on-edit description)
                        :loaded-fn (:loaded-fn description)}])
   (when categories
     [card-categories {:item item
                       :selector-fn (:selector-fn categories)
                       :relations-prefix (:relations-prefix categories)}])
   (when footer
     [:div.item-actions
      (into [:div.item-actions-left]
            (map-indexed (fn [i spec] ^{:key (str "l-" i)} [footer-widget spec]) (:left footer)))
      (into [:div.item-actions-right]
            (map-indexed (fn [i spec] ^{:key (str "r-" i)} [footer-widget spec]) (:right footer)))])
   expanded-suffix])

(defn- card-readonly-body [{:keys [item categories readonly-extra]}]
  [:<>
   (cond
     (:readonly-fn categories)
     [(:readonly-fn categories) item]

     categories
     [:div.item-tags-readonly
      [task-item/category-badges
       {:item item
        :category-types [[state/CATEGORY-TYPE-PERSON :people]
                         [state/CATEGORY-TYPE-PLACE :places]
                         [state/CATEGORY-TYPE-PROJECT :projects]
                         [state/CATEGORY-TYPE-GOAL :goals]]
        :toggle-fn state/toggle-shared-filter
        :has-filter-fn state/has-filter-for-type?}]
      (when (and (:relations-prefix categories)
                 (not (false? (:readonly-relations? categories)))
                 (seq (:relations item)))
        [relation-badges/relation-badges-collapsed (:relations item) (:relations-prefix categories) (:id item)])])
   readonly-extra])

(defn item-card [{:keys [item expanded? on-toggle container
                         relation-link inline-edit badges toolbar date
                         description categories footer
                         title-extra title-expanded-click title-content title-text-class
                         header-wrapper header-extra readonly-extra
                         expanded-prefix expanded-suffix]}]
  (let [{:keys [tag class attrs classes]} container
        tag (or tag :li)
        header-class (get classes :header "item-header")
        title-class (get classes :title "item-title")
        date-class (get classes :date "item-date")
        content-class (get classes :content "item-details")
        container-class (str/join " " (filter seq [(when expanded? "expanded") class]))
        header [card-header {:item item
                             :expanded? expanded?
                             :on-toggle on-toggle
                             :inline-edit inline-edit
                             :header-class header-class
                             :title-class title-class
                             :relation-link relation-link
                             :badges badges
                             :title-extra title-extra
                             :title-expanded-click title-expanded-click
                             :title-content title-content
                             :title-text-class title-text-class
                             :toolbar toolbar
                             :date date
                             :date-class date-class
                             :header-extra header-extra}]]
    [tag (merge {:class container-class} attrs)
     (if header-wrapper (header-wrapper header) header)
     (if expanded?
       [card-expanded-body {:item item
                            :content-class content-class
                            :description description
                            :categories categories
                            :footer footer
                            :expanded-prefix expanded-prefix
                            :expanded-suffix expanded-suffix}]
       [card-readonly-body {:item item
                            :categories categories
                            :readonly-extra readonly-extra}])]))
