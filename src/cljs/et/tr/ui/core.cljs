(ns et.tr.ui.core
  (:require [reagent.dom :as rdom]
            [reagent.core :as r]
            [et.tr.ui.state :as state]
            [et.tr.i18n :as i18n :refer [t tf]]))

(def ^:private category-shortcut-keys
  {"Digit1" :people
   "Digit2" :places
   "Digit3" :projects
   "Digit4" :goals})

(def ^:private category-shortcut-numbers
  (into {} (map (fn [[k v]] [v (subs k 5)]) category-shortcut-keys)))

(defn login-form []
  (let [username (r/atom "")
        password (r/atom "")]
    (fn []
      (let [do-login (fn []
                       (state/login @username @password
                                    (fn []
                                      (reset! username "")
                                      (reset! password "")
                                      (state/fetch-tasks)
                                      (state/fetch-people)
                                      (state/fetch-places)
                                      (state/fetch-projects)
                                      (state/fetch-goals))))]
        [:div.login-form
         [:h2 (t :auth/login)]
         (when-let [error (:error @state/app-state)]
           [:div.error error [:button.error-dismiss {:on-click state/clear-error} "×"]])
         [:input {:type "text"
                  :placeholder (t :auth/username)
                  :value @username
                  :on-change #(reset! username (-> % .-target .-value))
                  :on-key-down (fn [e]
                                 (when (= (.-key e) "Enter")
                                   (do-login)))}]
         [:input {:type "password"
                  :placeholder (t :auth/password)
                  :value @password
                  :on-change #(reset! password (-> % .-target .-value))
                  :on-key-down (fn [e]
                                 (when (= (.-key e) "Enter")
                                   (do-login)))}]
         [:button {:on-click (fn [_] (do-login))}
          (t :auth/login)]]))))

(defn add-task-form []
  (let [title (r/atom "")]
    (fn []
      [:div.add-form
       [:input {:type "text"
                :placeholder (t :tasks/add-placeholder)
                :value @title
                :on-change #(reset! title (-> % .-target .-value))
                :on-key-down #(when (= (.-key %) "Enter")
                                (state/add-task @title (fn [] (reset! title ""))))}]
       [:button {:on-click #(state/add-task @title (fn [] (reset! title "")))}
        (t :tasks/add-button)]])))

(defn add-entity-form [placeholder add-fn]
  (let [name (r/atom "")]
    (fn []
      [:div.add-entity-form
       [:input {:type "text"
                :placeholder placeholder
                :value @name
                :on-change #(reset! name (-> % .-target .-value))
                :on-key-down #(when (= (.-key %) "Enter")
                                (add-fn @name (fn [] (reset! name ""))))}]
       [:button {:on-click #(add-fn @name (fn [] (reset! name "")))}
        "+"]])))

(defn tabs []
  (let [active-tab (:active-tab @state/app-state)]
    [:div.tabs
     [:button.tab
      {:class (when (= active-tab :today) "active")
       :on-click #(state/set-active-tab :today)}
      (t :nav/today)]
     [:button.tab
      {:class (when (= active-tab :tasks) "active")
       :on-click #(state/set-active-tab :tasks)}
      (t :nav/tasks)]
     [:button.tab
      {:class (when (= active-tab :people-places) "active")
       :on-click #(state/set-active-tab :people-places)}
      (t :nav/people-places)]
     [:button.tab
      {:class (when (= active-tab :projects-goals) "active")
       :on-click #(state/set-active-tab :projects-goals)}
      (t :nav/projects-goals)]]))

(defn task-category-badges [task]
  (let [all-categories (concat
                        (map #(assoc % :type "person") (:people task))
                        (map #(assoc % :type "place") (:places task))
                        (map #(assoc % :type "project") (:projects task))
                        (map #(assoc % :type "goal") (:goals task)))]
    (when (seq all-categories)
      [:div.task-badges
       (for [category all-categories]
         ^{:key (str (:type category) "-" (:id category))}
         [:span.tag {:class (:type category)} (:name category)])])))

(defn today-task-item [task & {:keys [show-day-of-week show-day-prefix] :or {show-day-of-week false show-day-prefix false}}]
  (let [show-prefix? (and show-day-prefix (state/within-days? (:due_date task) 6))
        expanded-task (:today-page/expanded-task @state/app-state)
        is-expanded (= expanded-task (:id task))]
    [:div.today-task-item {:class (when is-expanded "expanded")}
     [:div.today-task-header
      {:on-click #(state/toggle-today-expanded (:id task))}
      [:div.today-task-content
       [:span.task-title
        (when show-prefix?
          [:span.task-day-prefix (state/get-day-name (:due_date task))])
        (when (seq (:due_time task))
          [:span.task-time (:due_time task)])
        (:title task)]
       (when-not is-expanded
         [task-category-badges task])]
      [:span.task-date
       (cond
         show-prefix? (:due_date task)
         show-day-of-week (state/format-date-with-day (:due_date task))
         :else (:due_date task))]]
     (when is-expanded
       [:div.today-task-details
        (when (seq (:description task))
          [:div.item-description (:description task)])
        [task-category-badges task]
        [:div.item-actions
         (if (= 1 (:done task))
           [:button.undone-btn {:on-click #(state/set-task-done (:id task) false)} (t :task/set-undone)]
           [:button.done-btn {:on-click #(state/set-task-done (:id task) true)} (t :task/mark-done)])
         [:button.delete-btn {:on-click #(state/set-confirm-delete-task task)} (t :task/delete)]]])]))

(defn horizon-selector []
  (let [horizon (:upcoming-horizon @state/app-state)]
    [:div.horizon-selector
     [:span (t :today/show)]
     [:button {:class (when (= horizon :three-days) "active")
               :on-click #(state/set-upcoming-horizon :three-days)} (t :today/three-days)]
     [:button {:class (when (= horizon :week) "active")
               :on-click #(state/set-upcoming-horizon :week)} (t :today/week)]
     [:button {:class (when (= horizon :month) "active")
               :on-click #(state/set-upcoming-horizon :month)} (t :today/month)]
     [:button {:class (when (= horizon :three-months) "active")
               :on-click #(state/set-upcoming-horizon :three-months)} (t :today/three-months)]
     [:button {:class (when (= horizon :year) "active")
               :on-click #(state/set-upcoming-horizon :year)} (t :today/year)]
     [:button {:class (when (= horizon :eighteen-months) "active")
               :on-click #(state/set-upcoming-horizon :eighteen-months)} (t :today/eighteen-months)]]))

(defn category-filter-section
  [{:keys [title shortcut-number filter-key items marked-ids toggle-fn clear-fn collapsed?
           toggle-collapsed-fn set-search-fn search-state-path
           section-class item-active-class label-class page-prefix]}]
  (let [marked-items (filter #(contains? marked-ids (:id %)) items)
        search-term (get-in @state/app-state search-state-path "")
        visible-items (if (seq search-term)
                        (filter #(state/prefix-matches? (:name %) search-term) items)
                        items)
        input-id (str (or page-prefix "tasks") "-filter-" (name filter-key))]
    [:div.filter-section {:class section-class}
     [:div.filter-header
      [:button.collapse-toggle
       {:on-click #(toggle-collapsed-fn filter-key)}
       (if collapsed? ">" "v")]
      (when shortcut-number
        [:span.shortcut-number {:title (str "Press Option+" shortcut-number " to toggle")}
         shortcut-number])
      (str " " title)
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
          :on-key-down #(when (= (.-key %) "Escape")
                          (toggle-collapsed-fn filter-key))}]
        (doall
         (for [item visible-items]
           ^{:key (:id item)}
           [:button.filter-item
            {:class (when (contains? marked-ids (:id item)) item-active-class)
             :on-click #(toggle-fn (:id item))}
            (:name item)]))])]))

(defn today-sidebar-filters []
  (let [{:keys [places projects]} @state/app-state
        excluded-places (:today-page/excluded-places @state/app-state)
        excluded-projects (:today-page/excluded-projects @state/app-state)
        collapsed-filters (:today-page/collapsed-filters @state/app-state)]
    [:div.sidebar
     [category-filter-section {:title (t :category/places)
                               :filter-key :places
                               :items places
                               :marked-ids excluded-places
                               :toggle-fn state/toggle-today-excluded-place
                               :clear-fn state/clear-today-excluded-places
                               :collapsed? (contains? collapsed-filters :places)
                               :toggle-collapsed-fn state/toggle-today-filter-collapsed
                               :set-search-fn state/set-today-category-search
                               :search-state-path [:today-page/category-search :places]
                               :section-class "exclusion-filter"
                               :item-active-class "excluded"
                               :label-class "excluded"
                               :page-prefix "today"}]
     [category-filter-section {:title (t :category/projects)
                               :filter-key :projects
                               :items projects
                               :marked-ids excluded-projects
                               :toggle-fn state/toggle-today-excluded-project
                               :clear-fn state/clear-today-excluded-projects
                               :collapsed? (contains? collapsed-filters :projects)
                               :toggle-collapsed-fn state/toggle-today-filter-collapsed
                               :set-search-fn state/set-today-category-search
                               :search-state-path [:today-page/category-search :projects]
                               :section-class "exclusion-filter"
                               :item-active-class "excluded"
                               :label-class "excluded"
                               :page-prefix "today"}]]))

(defn today-tab []
  (let [overdue (state/overdue-tasks)
        today (state/today-tasks)
        upcoming (state/upcoming-tasks)]
    [:div.main-layout
     [today-sidebar-filters]
     [:div.main-content.today-content
      (when (seq overdue)
        [:div.today-section.overdue
         [:h3 (t :today/overdue)]
         [:div.task-list
          (for [task overdue]
            ^{:key (:id task)}
            [today-task-item task])]])
      [:div.today-section.today
       [:h3 (state/today-formatted)]
       (if (seq today)
         [:div.task-list
          (for [task today]
            ^{:key (:id task)}
            [today-task-item task])]
         [:p.empty-message (t :today/no-today)])]
      [:div.today-section.upcoming
       [:div.section-header
        [:h3 (t :today/upcoming)]
        [horizon-selector]]
       (if (seq upcoming)
         [:div.task-list
          (for [task upcoming]
            ^{:key (:id task)}
            [today-task-item task :show-day-of-week true :show-day-prefix true])]
         [:p.empty-message (t :today/no-upcoming)])]]]))

(defn search-filter []
  (let [search-term (:tasks-page/filter-search @state/app-state)]
    [:div.search-filter
     [:input#tasks-search {:type "text"
                           :placeholder (t :tasks/search)
                           :value search-term
                           :on-change #(state/set-filter-search (-> % .-target .-value))
                           :on-key-down #(when (= (.-key %) "Escape")
                                           (state/set-filter-search ""))}]
     (when (seq search-term)
       [:button.clear-search {:on-click #(state/set-filter-search "")} "x"])]))

(defn sort-mode-toggle []
  (let [sort-mode (:sort-mode @state/app-state)]
    [:div.sort-toggle
     [:button {:class (when (= sort-mode :manual) "active")
               :on-click #(when (not= sort-mode :manual) (state/set-sort-mode :manual))}
      (t :tasks/sort-manual)]
     [:button {:class (when (= sort-mode :due-date) "active")
               :on-click #(when (not= sort-mode :due-date) (state/set-sort-mode :due-date))}
      (t :tasks/sort-due-date)]
     [:button {:class (when (= sort-mode :recent) "active")
               :on-click #(when (not= sort-mode :recent) (state/set-sort-mode :recent))}
      (t :tasks/sort-recent)]
     [:button {:class (when (= sort-mode :done) "active")
               :on-click #(when (not= sort-mode :done) (state/set-sort-mode :done))}
      (t :tasks/sort-done)]]))

(defn filter-section [{:keys [title filter-key items selected-ids toggle-fn clear-fn collapsed? number]}]
  [category-filter-section {:title title
                            :shortcut-number number
                            :filter-key filter-key
                            :items items
                            :marked-ids selected-ids
                            :toggle-fn toggle-fn
                            :clear-fn clear-fn
                            :collapsed? collapsed?
                            :toggle-collapsed-fn state/toggle-filter-collapsed
                            :set-search-fn state/set-category-search
                            :search-state-path [:tasks-page/category-search filter-key]
                            :section-class nil
                            :item-active-class "active"
                            :label-class nil}])

(defn sidebar-filters []
  (let [{:keys [people places projects goals]} @state/app-state
        filter-people (:tasks-page/filter-people @state/app-state)
        filter-places (:tasks-page/filter-places @state/app-state)
        filter-projects (:tasks-page/filter-projects @state/app-state)
        filter-goals (:tasks-page/filter-goals @state/app-state)
        collapsed-filters (:tasks-page/collapsed-filters @state/app-state)]
    [:div.sidebar
     [filter-section {:title (t :category/people)
                      :filter-key :people
                      :items people
                      :selected-ids filter-people
                      :toggle-fn state/toggle-filter-person
                      :clear-fn state/clear-filter-people
                      :collapsed? (contains? collapsed-filters :people)
                      :number (category-shortcut-numbers :people)}]
     [filter-section {:title (t :category/places)
                      :filter-key :places
                      :items places
                      :selected-ids filter-places
                      :toggle-fn state/toggle-filter-place
                      :clear-fn state/clear-filter-places
                      :collapsed? (contains? collapsed-filters :places)
                      :number (category-shortcut-numbers :places)}]
     [filter-section {:title (t :category/projects)
                      :filter-key :projects
                      :items projects
                      :selected-ids filter-projects
                      :toggle-fn state/toggle-filter-project
                      :clear-fn state/clear-filter-projects
                      :collapsed? (contains? collapsed-filters :projects)
                      :number (category-shortcut-numbers :projects)}]
     [filter-section {:title (t :category/goals)
                      :filter-key :goals
                      :items goals
                      :selected-ids filter-goals
                      :toggle-fn state/toggle-filter-goal
                      :clear-fn state/clear-filter-goals
                      :collapsed? (contains? collapsed-filters :goals)
                      :number (category-shortcut-numbers :goals)}]]))

(defn category-edit-form [item category-type update-fn]
  (let [name-val (r/atom (:name item))
        description-val (r/atom (or (:description item) ""))]
    (fn []
      [:div.item-edit-form
       [:input {:type "text"
                :value @name-val
                :on-change #(reset! name-val (-> % .-target .-value))
                :placeholder (t :category/name-placeholder)}]
       [:textarea {:value @description-val
                   :on-change #(reset! description-val (-> % .-target .-value))
                   :placeholder (t :category/description-placeholder)
                   :rows 3}]
       [:div.edit-buttons
        [:button {:on-click (fn []
                              (update-fn (:id item) @name-val @description-val
                                         #(state/clear-editing-category)))}
         (t :task/save)]
        [:button.cancel {:on-click #(state/clear-editing-category)}
         (t :task/cancel)]
        [:button.delete-btn {:on-click #(state/set-confirm-delete-category category-type item)}
         (t :category/delete)]]])))

(defn category-item [item category-type update-fn state-key]
  (let [editing (:category-page/editing @state/app-state)
        is-editing (and editing
                        (= (:type editing) category-type)
                        (= (:id editing) (:id item)))
        drag-cat (:drag-category @state/app-state)
        drag-over-cat (:drag-over-category @state/app-state)
        is-dragging (and drag-cat
                         (= (:type drag-cat) state-key)
                         (= (:id drag-cat) (:id item)))
        is-drag-over (and drag-over-cat
                          (= (:type drag-over-cat) state-key)
                          (= (:id drag-over-cat) (:id item)))]
    (if is-editing
      [category-edit-form item category-type update-fn]
      [:li {:class (str (when is-dragging "dragging")
                        (when is-drag-over " drag-over"))
            :draggable (not is-editing)
            :on-click #(state/set-editing-category category-type (:id item))
            :on-drag-start (fn [e]
                             (.setData (.-dataTransfer e) "text/plain" (str (:id item)))
                             (state/set-drag-category state-key (:id item)))
            :on-drag-end (fn [_]
                           (state/clear-category-drag-state))
            :on-drag-over (fn [e]
                            (.preventDefault e)
                            (state/set-drag-over-category state-key (:id item)))
            :on-drag-leave (fn [_]
                             (when (and drag-over-cat
                                        (= (:type drag-over-cat) state-key)
                                        (= (:id drag-over-cat) (:id item)))
                               (state/set-drag-over-category nil nil)))
            :on-drop (fn [e]
                       (.preventDefault e)
                       (when (and drag-cat
                                  (= (:type drag-cat) state-key)
                                  (not= (:id drag-cat) (:id item)))
                         (let [rect (.getBoundingClientRect (.-currentTarget e))
                               y (.-clientY e)
                               mid-y (+ (.-top rect) (/ (.-height rect) 2))
                               position (if (< y mid-y) "before" "after")]
                           (state/reorder-category state-key (:id drag-cat) (:id item) position))))}
       [:span.category-name (:name item)]
       (when (seq (:description item))
         [:span.category-description (:description item)])])))

(defn people-places-tab []
  (let [{:keys [people places]} @state/app-state]
    [:div.manage-tab
     [:div.manage-section
      [:h3 (t :category/people)]
      [add-entity-form (t :category/add-person) state/add-person]
      [:ul.entity-list
       (for [person people]
         ^{:key (:id person)}
         [category-item person :person state/update-person :people])]]
     [:div.manage-section
      [:h3 (t :category/places)]
      [add-entity-form (t :category/add-place) state/add-place]
      [:ul.entity-list
       (for [place places]
         ^{:key (:id place)}
         [category-item place :place state/update-place :places])]]]))

(defn projects-goals-tab []
  (let [{:keys [projects goals]} @state/app-state]
    [:div.manage-tab
     [:div.manage-section
      [:h3 (t :category/projects)]
      [add-entity-form (t :category/add-project) state/add-project]
      [:ul.entity-list
       (for [project projects]
         ^{:key (:id project)}
         [category-item project :project state/update-project :projects])]]
     [:div.manage-section
      [:h3 (t :category/goals)]
      [add-entity-form (t :category/add-goal) state/add-goal]
      [:ul.entity-list
       (for [goal goals]
         ^{:key (:id goal)}
         [category-item goal :goal state/update-goal :goals])]]]))

(defn add-user-form []
  (let [username (r/atom "")
        password (r/atom "")]
    (fn []
      [:div.add-user-form
       [:input {:type "text"
                :placeholder (t :auth/username)
                :value @username
                :on-change #(reset! username (-> % .-target .-value))}]
       [:input {:type "password"
                :placeholder (t :auth/password)
                :value @password
                :on-change #(reset! password (-> % .-target .-value))
                :on-key-down #(when (= (.-key %) "Enter")
                                (state/add-user @username @password
                                                (fn []
                                                  (reset! username "")
                                                  (reset! password ""))))}]
       [:button {:on-click #(state/add-user @username @password
                                            (fn []
                                              (reset! username "")
                                              (reset! password "")))}
        (t :users/add-button)]])))

(defn users-tab []
  (let [{:keys [users]} @state/app-state]
    [:div.manage-tab
     [:div.manage-section
      [:h3 (t :users/title)]
      [add-user-form]
      [:ul.entity-list.user-list
       (for [user users]
         ^{:key (:id user)}
         [:li
          [:span.username (:username user)]
          [:button.delete-user-btn
           {:on-click #(state/set-confirm-delete-user user)}
           (t :task/delete)]])]]]))

(defn category-selector [task category-type entities label]
  (let [selector-id (str (:id task) "-" category-type)
        input-id (str "category-selector-input-" selector-id)]
    (fn [task category-type entities label]
      (let [task-categories (case category-type
                              "person" (:people task)
                              "place" (:places task)
                              "project" (:projects task)
                              "goal" (:goals task))
            task-category-ids (set (map :id task-categories))
            open-selector (:category-selector/open @state/app-state)
            is-open (= open-selector selector-id)
            search-term (:category-selector/search @state/app-state)
            available-entities (remove #(contains? task-category-ids (:id %)) entities)
            filtered-entities (if (and is-open (seq search-term))
                                (filter #(state/prefix-matches? (:name %) search-term) available-entities)
                                available-entities)]
        [:div.tag-selector
         [:div.category-selector-dropdown
          [:button.category-selector-trigger
           {:class (str category-type (when is-open " open"))
            :on-click (fn [e]
                        (.stopPropagation e)
                        (if is-open
                          (do
                            (state/close-category-selector)
                            (state/focus-tasks-search))
                          (do
                            (state/open-category-selector selector-id)
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
               :value search-term
               :auto-focus true
               :on-change #(state/set-category-selector-search (-> % .-target .-value))
               :on-key-down (fn [e]
                              (case (.-key e)
                                "Escape" (do
                                           (state/close-category-selector)
                                           (state/focus-tasks-search))
                                "Enter" (when (= 1 (count filtered-entities))
                                          (state/categorize-task (:id task) category-type (:id (first filtered-entities)))
                                          (state/close-category-selector)
                                          (state/focus-tasks-search))
                                nil))}]
             [:div.category-selector-items
              (if (seq filtered-entities)
                (doall
                 (for [entity filtered-entities]
                   ^{:key (:id entity)}
                   [:button.category-selector-item
                    {:class category-type
                     :on-click (fn [e]
                                 (.stopPropagation e)
                                 (state/categorize-task (:id task) category-type (:id entity))
                                 (state/close-category-selector)
                                 (state/focus-tasks-search))}
                    (:name entity)]))
                [:div.category-selector-empty (t :category/no-results)])]])]
         (for [category task-categories]
           ^{:key (str category-type "-" (:id category))}
           [:span.tag
            {:class category-type}
            (:name category)
            [:button.remove-tag
             {:on-click #(state/uncategorize-task (:id task) category-type (:id category))}
             "x"]])]))))

(defn task-edit-form [task]
  (let [title (r/atom (:title task))
        description (r/atom (or (:description task) ""))]
    (fn []
      [:div.item-edit-form
       [:input {:type "text"
                :value @title
                :on-change #(reset! title (-> % .-target .-value))
                :placeholder (t :task/title-placeholder)}]
       [:textarea {:value @description
                   :on-change #(reset! description (-> % .-target .-value))
                   :placeholder (t :task/description-placeholder)
                   :rows 3}]
       [:div.edit-buttons
        [:button {:on-click (fn []
                              (state/update-task (:id task) @title @description
                                                 #(state/clear-editing)))}
         (t :task/save)]
        [:button.cancel {:on-click #(state/clear-editing)}
         (t :task/cancel)]]])))

(defn task-categories-readonly [task]
  [:div.item-tags-readonly
   [task-category-badges task]])

(defn tasks-list []
  (let [{:keys [people places projects goals expanded-task editing-task sort-mode drag-task drag-over-task]} @state/app-state
        tasks (state/filtered-tasks)
        manual-mode? (= sort-mode :manual)
        due-date-mode? (= sort-mode :due-date)
        done-mode? (= sort-mode :done)]
    [:ul.items
     (for [task tasks]
       (let [is-expanded (= expanded-task (:id task))
             is-editing (= editing-task (:id task))
             is-dragging (= drag-task (:id task))
             is-drag-over (= drag-over-task (:id task))]
         ^{:key (:id task)}
         [:li {:class (str (when is-expanded "expanded")
                           (when is-dragging " dragging")
                           (when is-drag-over " drag-over"))
               :draggable (and manual-mode? (not is-editing))
               :on-drag-start (fn [e]
                                (when manual-mode?
                                  (.setData (.-dataTransfer e) "text/plain" (str (:id task)))
                                  (state/set-drag-task (:id task))))
               :on-drag-end (fn [_]
                              (state/clear-drag-state))
               :on-drag-over (fn [e]
                               (when manual-mode?
                                 (.preventDefault e)
                                 (state/set-drag-over-task (:id task))))
               :on-drag-leave (fn [_]
                                (when (= drag-over-task (:id task))
                                  (state/set-drag-over-task nil)))
               :on-drop (fn [e]
                          (.preventDefault e)
                          (when (and manual-mode? drag-task (not= drag-task (:id task)))
                            (let [rect (.getBoundingClientRect (.-currentTarget e))
                                  y (.-clientY e)
                                  mid-y (+ (.-top rect) (/ (.-height rect) 2))
                                  position (if (< y mid-y) "before" "after")]
                              (state/reorder-task drag-task (:id task) position))))}
          (if is-editing
            [task-edit-form task]
            [:div
             [:div.item-header
              {:on-click #(state/toggle-expanded (:id task))}
              [:div.item-title
               (when (seq (:due_time task))
                 [:span.task-time (:due_time task)])
               (:title task)
               (when is-expanded
                 [:<>
                  [:button.edit-icon {:on-click (fn [e]
                                                  (.stopPropagation e)
                                                  (state/set-editing (:id task)))}
                   "✎"]
                  [:span.date-picker-wrapper
                   {:on-click #(.stopPropagation %)}
                   [:input.date-picker-input
                    {:type "date"
                     :value (or (:due_date task) "")
                     :on-change (fn [e]
                                  (let [v (.. e -target -value)]
                                    (state/set-task-due-date (:id task) (when (seq v) v))))}]
                   [:button.calendar-icon {:on-click (fn [e]
                                                       (.stopPropagation e)
                                                       (-> e .-currentTarget .-parentElement (.querySelector "input") .showPicker))}
                    "📅"]]
                  (when (:due_date task)
                    [:span.time-picker-wrapper
                     {:on-click #(.stopPropagation %)}
                     [:input.time-picker-input
                      {:type "time"
                       :value (or (:due_time task) "")
                       :on-change (fn [e]
                                    (let [v (.. e -target -value)]
                                      (state/set-task-due-time (:id task) (when (seq v) v))))}]
                     [:button.clock-icon {:on-click (fn [e]
                                                      (.stopPropagation e)
                                                      (-> e .-currentTarget .-parentElement (.querySelector "input") .showPicker))}
                      "🕐"]])])]
              [:div.item-date
               (when (and (:due_date task) (not done-mode?))
                 (let [today (state/today-str)
                       overdue? (< (:due_date task) today)]
                   [:span.due-date {:class (when overdue? "overdue")} (state/format-date-with-day (:due_date task))]))
               (when-not due-date-mode?
                 [:span (:modified_at task)])]]
             (if is-expanded
               [:div.item-details
                (when (seq (:description task))
                  [:div.item-description (:description task)])
                [:div.item-tags
                 [category-selector task "person" people (t :category/person)]
                 [category-selector task "place" places (t :category/place)]
                 [category-selector task "project" projects (t :category/project)]
                 [category-selector task "goal" goals (t :category/goal)]]
                [:div.item-actions
                 (if (= 1 (:done task))
                   [:button.undone-btn {:on-click #(state/set-task-done (:id task) false)} (t :task/set-undone)]
                   [:button.done-btn {:on-click #(state/set-task-done (:id task) true)} (t :task/mark-done)])
                 [:button.delete-btn {:on-click #(state/set-confirm-delete-task task)} (t :task/delete)]]]
               [task-categories-readonly task])])]))]))

(defn confirm-delete-modal []
  (when-let [task (:confirm-delete-task @state/app-state)]
    [:div.modal-overlay {:on-click #(state/clear-confirm-delete)}
     [:div.modal {:on-click #(.stopPropagation %)}
      [:div.modal-header (t :modal/delete-task)]
      [:div.modal-body
       [:p (t :modal/delete-task-confirm)]
       [:p.task-title (:title task)]]
      [:div.modal-footer
       [:button.cancel {:on-click #(state/clear-confirm-delete)} (t :modal/cancel)]
       [:button.confirm-delete {:on-click #(state/delete-task (:id task))} (t :modal/delete)]]]]))

(defn confirm-delete-user-modal []
  (let [confirmation-input (r/atom "")]
    (fn []
      (when-let [user (:confirm-delete-user @state/app-state)]
        (let [username (:username user)
              matches? (= @confirmation-input username)]
          [:div.modal-overlay {:on-click #(do (reset! confirmation-input "") (state/clear-confirm-delete-user))}
           [:div.modal {:on-click #(.stopPropagation %)}
            [:div.modal-header (t :modal/delete-user)]
            [:div.modal-body
             [:p (t :modal/delete-user-confirm)]
             [:p.task-title username]
             [:p.warning (t :modal/delete-user-warning)]
             [:p {:style {:margin-top "16px"}} (tf :modal/delete-user-type-confirm username)]
             [:input {:type "text"
                      :value @confirmation-input
                      :on-change #(reset! confirmation-input (-> % .-target .-value))
                      :placeholder (t :modal/enter-username)
                      :style {:width "100%" :margin-top "8px"}}]]
            [:div.modal-footer
             [:button.cancel {:on-click #(do (reset! confirmation-input "") (state/clear-confirm-delete-user))} (t :modal/cancel)]
             [:button.confirm-delete {:disabled (not matches?)
                                      :on-click #(do (reset! confirmation-input "") (state/delete-user (:id user)))} (t :modal/delete)]]]])))))

(defn confirm-delete-category-modal []
  (when-let [{:keys [type category]} (:confirm-delete-category @state/app-state)]
    (let [type-label (case type
                       :person (t :category/person)
                       :place (t :category/place)
                       :project (t :category/project)
                       :goal (t :category/goal)
                       type)
          delete-fn (case type
                      :person state/delete-person
                      :place state/delete-place
                      :project state/delete-project
                      :goal state/delete-goal)]
      [:div.modal-overlay {:on-click #(state/clear-confirm-delete-category)}
       [:div.modal {:on-click #(.stopPropagation %)}
        [:div.modal-header (tf :modal/delete-category type-label)]
        [:div.modal-body
         [:p (tf :modal/delete-category-confirm type-label)]
         [:p.task-title (:name category)]
         [:p.warning (tf :modal/delete-category-warning type-label)]]
        [:div.modal-footer
         [:button.cancel {:on-click #(state/clear-confirm-delete-category)} (t :modal/cancel)]
         [:button.confirm-delete {:on-click #(delete-fn (:id category))} (t :modal/delete)]]]])))

(defn category-tag-item [category-type id name selected? toggle-fn]
  [:span.tag.selectable
   {:class (str category-type (when selected? " selected"))
    :on-click #(toggle-fn category-type id)}
   name
   (when selected? [:span.check " ✓"])])

(defn pending-task-modal []
  (when-let [{:keys [title categories]} (:pending-new-task @state/app-state)]
    (let [{:keys [people places projects goals]} @state/app-state
          {:keys [people places projects goals]
           :as selected} categories
          selected-people (or people #{})
          selected-places (or places #{})
          selected-projects (or projects #{})
          selected-goals (or goals #{})]
      [:div.modal-overlay {:on-click #(state/clear-pending-new-task)}
       [:div.modal.pending-task-modal {:on-click #(.stopPropagation %)}
        [:div.modal-header (t :modal/add-task-categories)]
        [:div.modal-body
         [:p.task-title title]
         [:p.modal-instruction (t :modal/select-categories)]
         (when (seq (:people @state/app-state))
           [:div.category-group
            [:label (str (t :category/people) ":")]
            [:div.category-tags
             (for [p (:people @state/app-state)]
               ^{:key (:id p)}
               [category-tag-item "person" (:id p) (:name p)
                (contains? selected-people (:id p))
                state/update-pending-category])]])
         (when (seq (:places @state/app-state))
           [:div.category-group
            [:label (str (t :category/places) ":")]
            [:div.category-tags
             (for [p (:places @state/app-state)]
               ^{:key (:id p)}
               [category-tag-item "place" (:id p) (:name p)
                (contains? selected-places (:id p))
                state/update-pending-category])]])
         (when (seq (:projects @state/app-state))
           [:div.category-group
            [:label (str (t :category/projects) ":")]
            [:div.category-tags
             (for [p (:projects @state/app-state)]
               ^{:key (:id p)}
               [category-tag-item "project" (:id p) (:name p)
                (contains? selected-projects (:id p))
                state/update-pending-category])]])
         (when (seq (:goals @state/app-state))
           [:div.category-group
            [:label (str (t :category/goals) ":")]
            [:div.category-tags
             (for [g (:goals @state/app-state)]
               ^{:key (:id g)}
               [category-tag-item "goal" (:id g) (:name g)
                (contains? selected-goals (:id g))
                state/update-pending-category])]])]
        [:div.modal-footer
         [:button.cancel {:on-click #(state/clear-pending-new-task)} (t :modal/cancel)]
         [:button.confirm {:on-click #(state/confirm-pending-new-task)} (t :modal/add-task)]]]])))

(defn user-switcher-dropdown []
  (let [available-users (:available-users @state/app-state)
        current-user (:current-user @state/app-state)]
    [:div.user-switcher-dropdown
     (for [user available-users]
       ^{:key (or (:id user) "admin")}
       [:div.user-switcher-item
        {:class (when (= (:id user) (:id current-user)) "active")
         :on-click #(state/switch-user user)}
        (:username user)])]))

(defn language-selector []
  (let [current-user (:current-user @state/app-state)
        current-lang (or (:language current-user) "en")]
    [:div.settings-item
     [:span.settings-label (t :settings/language)]
     [:select.language-select
      {:value current-lang
       :on-change #(state/update-user-language (-> % .-target .-value))}
      [:option {:value "en"} (t :settings/language-en)]
      [:option {:value "de"} (t :settings/language-de)]
      [:option {:value "pt"} (t :settings/language-pt)]]]))

(defn settings-tab []
  (let [current-user (:current-user @state/app-state)
        is-admin (:is_admin current-user)]
    [:div.manage-tab
     [:div.manage-section.settings-section
      [:h3 (t :settings/profile)]
      [:div.settings-item
       [:span.settings-label (t :settings/username)]
       [:span.settings-value (:username current-user)]]
      [:div.settings-item
       [:span.settings-label (t :settings/role)]
       [:span.settings-value (if is-admin (t :settings/role-admin) (t :settings/role-user))]]
      (when-not is-admin
        [language-selector])]
     [:div.manage-section.settings-section
      [:h3 (t :settings/data)]
      [:div.settings-item
       [:button.export-btn {:on-click #(state/export-data)}
        (t :settings/export-data)]]]]))

(defn user-info []
  (let [current-user (:current-user @state/app-state)
        active-tab (:active-tab @state/app-state)
        is-admin (state/is-admin?)
        auth-required? (:auth-required? @state/app-state)
        show-switcher (:show-user-switcher @state/app-state)]
    (when current-user
      [:div.user-info
       (when is-admin
         [:button.users-btn
          {:class (when (= active-tab :users) "active")
           :on-click #(state/set-active-tab :users)}
          (t :nav/users)])
       (if auth-required?
         [:<>
          [:button.username-btn
           {:class (when (= active-tab :settings) "active")
            :on-click #(state/set-active-tab :settings)}
           (:username current-user)]
          [:button.logout-btn {:on-click state/logout} (t :auth/logout)]]
         [:<>
          [:button.settings-btn
           {:class (when (= active-tab :settings) "active")
            :on-click #(state/set-active-tab :settings)}
           (t :nav/settings)]
          [:div.user-switcher-wrapper
           [:button.switch-user-btn
            {:on-click state/toggle-user-switcher}
            [:span.current-user (:username current-user)]
            [:span.dropdown-arrow (if show-switcher "▲" "▼")]]
           (when show-switcher
             [user-switcher-dropdown])]])])))

(defn app []
  (let [{:keys [auth-required? logged-in? active-tab sort-mode]} @state/app-state]
    [:div
     [confirm-delete-modal]
     [confirm-delete-user-modal]
     [confirm-delete-category-modal]
     [pending-task-modal]
     (cond
       (nil? auth-required?)
       [:div (t :auth/loading)]

       (and auth-required? (not logged-in?))
       [login-form]

       :else
       [:div
        (when-let [error (:error @state/app-state)]
          [:div.error error [:button.error-dismiss {:on-click state/clear-error} "×"]])
        [:div.top-bar
         [tabs]
         [user-info]]
        (case active-tab
          :today [today-tab]
          :people-places [people-places-tab]
          :projects-goals [projects-goals-tab]
          :users [users-tab]
          :settings [settings-tab]
          [:div.main-layout
           [sidebar-filters]
           [:div.main-content
            [:div.tasks-header
             [:h2 {:title (t :tasks/title-tooltip)} (t :tasks/title)]
             [sort-mode-toggle]]
            [search-filter]
            (when (and (not= sort-mode :done)
                       (empty? (:tasks-page/filter-search @state/app-state)))
              [add-task-form])
            [tasks-list]]])])]))

(defn- handle-category-shortcut [e]
  (when (and (.-altKey e)
             (= :tasks (:active-tab @state/app-state)))
    (when-let [filter-key (category-shortcut-keys (.-code e))]
      (.preventDefault e)
      (state/toggle-filter-collapsed filter-key))))

(defn init []
  (i18n/load-translations!
   (fn []
     (state/fetch-auth-required)
     (.addEventListener js/document "click"
                        (fn [_]
                          (when (:category-selector/open @state/app-state)
                            (state/close-category-selector))))
     (.removeEventListener js/document "keydown" handle-category-shortcut)
     (.addEventListener js/document "keydown" handle-category-shortcut)
     (rdom/render [app] (.getElementById js/document "app")))))
