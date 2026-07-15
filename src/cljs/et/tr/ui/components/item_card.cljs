(ns et.tr.ui.components.item-card
  (:require [clojure.string :as str]
            [reagent.core :as r]
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

(defn- variant-class [variant]
  (case variant
    :delete "delete-btn"
    :done "done"
    :undone "undone"
    :acknowledge "acknowledge-reminder"
    nil))

(defn close-on-unmount
  "Lifecycle wrapper: renders `child` and, when it unmounts, runs the most recent
  `close!` handed to it. Card action dropdowns keep their open-state in a global
  per-item key in an app atom, so a plain card collapse (which unmounts the
  expanded body) would otherwise leave that key pointing at a now-hidden card and
  the menu would re-open stale on the next expand. Routing a dropdown through
  this wrapper ties its open lifetime to its own mount lifetime, which makes the
  stale-open class of bug structurally impossible for any widget that uses it —
  not just the footer split-button. `close!` is a 0-arg fn and must be a no-op
  when the dropdown is already closed."
  [_ _]
  (let [close-ref (atom nil)]
    (r/create-class
     {:display-name "close-on-unmount"
      :component-will-unmount (fn [_] (when-let [c @close-ref] (c)))
      :reagent-render (fn [close! child]
                        (reset! close-ref close!)
                        child)})))

(defn footer-button
  "Split action button with an optional dropdown menu. The open state is held
  externally (a per-item key in an app atom) through :open?/:on-toggle; the
  dropdown is wrapped in `close-on-unmount` so the menu can never outlive its own
  card (a collapse swaps the expanded body for the read-only body, unmounting
  this button). Every card action dropdown (tasks, issues, mail, meets, reports)
  is declared as a footer-button spec, so they all inherit that guarantee — no
  call site has to remember to clear its state on collapse."
  [{:keys [label on-click variant dropdown class disabled title]}]
  (let [vclass (variant-class variant)
        main-class (str/join " " (remove nil? [vclass class (when disabled "disabled")]))
        {:keys [items open? on-toggle]} dropdown]
    [close-on-unmount
     (fn [] (when (and open? on-toggle) (on-toggle)))
     [:div.combined-button-wrapper
      (if (seq items)
        [:<>
         [:button.combined-main-btn {:class main-class :on-click on-click :disabled disabled :title title} label]
         [:button.combined-dropdown-btn {:class vclass :on-click on-toggle} "▼"]
         (when open?
           (into [:div.task-dropdown-menu]
                 (map-indexed
                   (fn [i {item-label :label item-click :on-click item-class :class item-title :title}]
                     ^{:key i}
                     [:button.dropdown-item {:class item-class :title item-title :on-click item-click}
                      item-label])
                   items)))]
        [:button.combined-main-btn.standalone {:class main-class :on-click on-click} label])]]))

(defn- card-title-el [{:keys [item expanded? inline-edit title-text-class]}]
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
       ;; Alt/Option-click a title to inline-edit it, whether the card is
       ;; expanded or collapsed.
       ;; A plain click falls through (no stopPropagation) to the header's
       ;; on-click, which toggles expand/collapse — the title never opens the
       ;; modal (that is the pencil's and the body/description's job).
       (when inline-edit
         {:on-click (fn [e]
                      (when (.-altKey e)
                        (.stopPropagation e)
                        (swap! a assoc
                               edit-id-path (:id item)
                               title-path (:title item))))})
       (:title item)])))

(defn- card-title-area [{:keys [item expanded? title-class relation-link inline-edit badges title-extra title-content title-text-class title-icon]}]
  (let [title-el [card-title-el {:item item
                                 :expanded? expanded?
                                 :inline-edit inline-edit
                                 :title-text-class title-text-class}]
        title-icon-el (when title-icon [:span.item-title-icon title-icon])]
    (if title-content
      [(keyword (str "div." title-class))
       (when relation-link
         [relation-link/relation-link-button (first relation-link) (second relation-link)])
       (title-content {:item item :expanded? expanded? :editing? (inline-editing? inline-edit item) :title-el title-el :title-icon-el title-icon-el})]
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
       title-icon-el
       title-el])))

;; Pointer position captured on the header's mousedown, read back on the
;; ensuing click to tell a plain click apart from a text-selection drag.
;; Only one pointer gesture is ever in flight, so a single shared atom is safe.
(defonce ^:private press-xy (atom nil))

(def ^:private drag-threshold-px 5)

(defn capture-press-xy [e]
  (reset! press-xy [(.-clientX e) (.-clientY e)]))

(defn pointer-dragged? [e]
  (let [[dx dy] @press-xy]
    (and dx (> (+ (js/Math.abs (- (.-clientX e) dx))
                  (js/Math.abs (- (.-clientY e) dy)))
               drag-threshold-px))))

(defn- card-header [{:keys [item expanded? on-toggle inline-edit header-class title-class
                            relation-link badges title-extra title-content title-text-class title-icon
                            toolbar date date-class header-extra]}]
  (let [editing? (inline-editing? inline-edit item)]
    [(keyword (str "div." header-class))
     {:on-mouse-down capture-press-xy
      ;; Toggle expand/collapse on a plain click. When the card is expanded we
      ;; skip the toggle if the pointer travelled more than a few pixels between
      ;; mousedown and click — that is a text-selection drag (e.g. selecting the
      ;; title), and collapsing would clobber the selection. Checking movement
      ;; (rather than window.getSelection) means an ordinary click with a 1-2px
      ;; drift still collapses instead of being swallowed by a stray 1-char
      ;; selection.
      :on-click (fn [e]
                  (when-not (or editing? (and expanded? (pointer-dragged? e)))
                    (on-toggle)))}
     [card-title-area {:item item
                       :expanded? expanded?
                       :title-class title-class
                       :relation-link relation-link
                       :inline-edit inline-edit
                       :badges badges
                       :title-extra title-extra
                       :title-content title-content
                       :title-text-class title-text-class
                       :title-icon title-icon}]
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
  (if (state/show-collapsed-categories?)
    (let [{:keys [people places projects goals]} @state/*app-state]
      [:div.item-tags
       (when relations-prefix
         [relation-badges/relation-badges-expanded (:relations item) relations-prefix (:id item)])
       [selector-fn item state/CATEGORY-TYPE-PERSON people (t :category/person)]
       [selector-fn item state/CATEGORY-TYPE-PLACE places (t :category/place)]
       [selector-fn item state/CATEGORY-TYPE-PROJECT projects (t :category/project)]
       [selector-fn item state/CATEGORY-TYPE-GOAL goals (t :category/goal)]])
    [:div.item-tags-readonly
     (when relations-prefix
       [relation-badges/relation-badges-expanded (:relations item) relations-prefix (:id item)])
     [task-item/category-badges
      {:item item
       :category-types [[state/CATEGORY-TYPE-PERSON :people]
                        [state/CATEGORY-TYPE-PLACE :places]
                        [state/CATEGORY-TYPE-PROJECT :projects]
                        [state/CATEGORY-TYPE-GOAL :goals]]
       :toggle-fn state/toggle-shared-filter
       :has-filter-fn state/has-filter-for-type?
       :force-show? true}]]))

(defn- card-description [{:keys [item edit-type content-type on-edit loaded-fn]}]
  (let [loaded? (if loaded-fn (loaded-fn item) true)
        open-edit (fn [] (if on-edit (on-edit item) (state/open-edit-modal edit-type item)))]
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
     (let [{:keys [left scope importance urgency main-actions]} footer]
       [:div.item-actions
        [:div.item-actions-left
         left
         (when (:on-set scope) [scope-selector scope])
         (when (:on-set importance) [importance-selector importance])
         (when (:on-set urgency) [urgency-selector urgency])]
        [:div.item-actions-right
         (when main-actions [footer-button main-actions])]]))
   expanded-suffix])

(defn- card-readonly-body [{:keys [item categories readonly-extra]}]
  [:<>
   (cond
     (:readonly-fn categories)
     [(:readonly-fn categories) item]

     categories
     [:div.item-tags-readonly
      (when (and (:relations-prefix categories)
                 (not (false? (:readonly-relations? categories)))
                 (seq (:relations item)))
        [relation-badges/relation-badges-collapsed (:relations item) (:relations-prefix categories) (:id item)])
      [task-item/category-badges
       {:item item
        :category-types [[state/CATEGORY-TYPE-PERSON :people]
                         [state/CATEGORY-TYPE-PLACE :places]
                         [state/CATEGORY-TYPE-PROJECT :projects]
                         [state/CATEGORY-TYPE-GOAL :goals]]
        :toggle-fn state/toggle-shared-filter
        :has-filter-fn state/has-filter-for-type?}]])
   readonly-extra])

(defn item-card [{:keys [item expanded? on-toggle container
                         relation-link inline-edit badges toolbar date
                         description categories footer
                         title-extra title-content title-text-class title-icon
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
                             :title-content title-content
                             :title-text-class title-text-class
                             :title-icon title-icon
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
