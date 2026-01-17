(ns et.tr.ui.core
  (:require [reagent.dom :as rdom]
            [reagent.core :as r]
            [et.tr.ui.state :as state]))

(defn login-form []
  (let [password (r/atom "")]
    (fn []
      [:div.login-form
       [:h2 "Login"]
       (when-let [error (:error @state/app-state)]
         [:div.error error])
       [:input {:type "password"
                :placeholder "Password"
                :value @password
                :on-change #(reset! password (-> % .-target .-value))
                :on-key-down (fn [e]
                               (when (= (.-key e) "Enter")
                                 (state/login @password (fn []
                                                          (reset! password "")
                                                          (state/fetch-tasks)))))}]
       [:button {:on-click (fn [_]
                             (state/login @password (fn []
                                                      (reset! password "")
                                                      (state/fetch-tasks))))}
        "Login"]])))

(defn add-task-form []
  (let [title (r/atom "")]
    (fn []
      [:div.add-form
       [:input {:type "text"
                :placeholder "Task title"
                :value @title
                :on-change #(reset! title (-> % .-target .-value))
                :on-key-down #(when (= (.-key %) "Enter")
                                (state/add-task @title (fn [] (reset! title ""))))}]
       [:button {:on-click #(state/add-task @title (fn [] (reset! title "")))}
        "Add"]])))

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
      "Today"]
     [:button.tab
      {:class (when (= active-tab :tasks) "active")
       :on-click #(state/set-active-tab :tasks)}
      "Tasks"]
     [:button.tab
      {:class (when (= active-tab :people-places) "active")
       :on-click #(state/set-active-tab :people-places)}
      "People & Places"]
     [:button.tab
      {:class (when (= active-tab :projects-goals) "active")
       :on-click #(state/set-active-tab :projects-goals)}
      "Projects & Goals"]]))

(defn today-tab []
  [:div.today-tab
   [:p "Here you'll find important things you should pay attention to."]])

(defn search-filter []
  (let [search-term (:filter-search @state/app-state)]
    [:div.search-filter
     [:input {:type "text"
              :placeholder "Search tasks..."
              :value search-term
              :on-change #(state/set-filter-search (-> % .-target .-value))}]
     (when (seq search-term)
       [:button.clear-search {:on-click #(state/set-filter-search "")} "x"])]))

(defn sort-mode-toggle []
  (let [sort-mode (:sort-mode @state/app-state)]
    [:div.sort-toggle
     [:button {:class (when (= sort-mode :recent) "active")
               :on-click #(when (not= sort-mode :recent) (state/set-sort-mode :recent))}
      "Recent first"]
     [:button {:class (when (= sort-mode :manual) "active")
               :on-click #(when (not= sort-mode :manual) (state/set-sort-mode :manual))}
      "Manual order"]]))

(defn filter-section [{:keys [title filter-key items selected-ids toggle-fn clear-fn collapsed?]}]
  (let [selected-items (filter #(contains? selected-ids (:id %)) items)]
    [:div.filter-section
     [:div.filter-header
      [:button.collapse-toggle
       {:on-click #(state/toggle-filter-collapsed filter-key)}
       (if collapsed? ">" "v")]
      title
      (when (seq selected-ids)
        [:button.clear-filter {:on-click clear-fn} "x"])]
     (if collapsed?
       (when (seq selected-items)
         [:div.filter-items.collapsed
          (doall
           (for [item selected-items]
             ^{:key (:id item)}
             [:span.filter-item-label
              (:name item)
              [:button.remove-item {:on-click #(toggle-fn (:id item))} "x"]]))])
       [:div.filter-items
        (doall
         (for [item items]
           ^{:key (:id item)}
           [:button.filter-item
            {:class (when (contains? selected-ids (:id item)) "active")
             :on-click #(toggle-fn (:id item))}
            (:name item)]))])]))

(defn sidebar-filters []
  (let [{:keys [people places projects goals filter-people filter-places filter-projects filter-goals collapsed-filters]} @state/app-state]
    [:div.sidebar
     [filter-section {:title "People"
                      :filter-key :people
                      :items people
                      :selected-ids filter-people
                      :toggle-fn state/toggle-filter-person
                      :clear-fn state/clear-filter-people
                      :collapsed? (contains? collapsed-filters :people)}]
     [filter-section {:title "Places"
                      :filter-key :places
                      :items places
                      :selected-ids filter-places
                      :toggle-fn state/toggle-filter-place
                      :clear-fn state/clear-filter-places
                      :collapsed? (contains? collapsed-filters :places)}]
     [filter-section {:title "Projects"
                      :filter-key :projects
                      :items projects
                      :selected-ids filter-projects
                      :toggle-fn state/toggle-filter-project
                      :clear-fn state/clear-filter-projects
                      :collapsed? (contains? collapsed-filters :projects)}]
     [filter-section {:title "Goals"
                      :filter-key :goals
                      :items goals
                      :selected-ids filter-goals
                      :toggle-fn state/toggle-filter-goal
                      :clear-fn state/clear-filter-goals
                      :collapsed? (contains? collapsed-filters :goals)}]]))

(defn people-places-tab []
  (let [{:keys [people places]} @state/app-state]
    [:div.manage-tab
     [:div.manage-section
      [:h3 "People"]
      [add-entity-form "Add person..." state/add-person]
      [:ul.entity-list
       (for [person people]
         ^{:key (:id person)}
         [:li (:name person)])]]
     [:div.manage-section
      [:h3 "Places"]
      [add-entity-form "Add place..." state/add-place]
      [:ul.entity-list
       (for [place places]
         ^{:key (:id place)}
         [:li (:name place)])]]]))

(defn projects-goals-tab []
  (let [{:keys [projects goals]} @state/app-state]
    [:div.manage-tab
     [:div.manage-section
      [:h3 "Projects"]
      [add-entity-form "Add project..." state/add-project]
      [:ul.entity-list
       (for [project projects]
         ^{:key (:id project)}
         [:li (:name project)])]]
     [:div.manage-section
      [:h3 "Goals"]
      [add-entity-form "Add goal..." state/add-goal]
      [:ul.entity-list
       (for [goal goals]
         ^{:key (:id goal)}
         [:li (:name goal)])]]]))

(defn category-selector [task category-type entities label]
  (let [task-categories (case category-type
                          "person" (:people task)
                          "place" (:places task)
                          "project" (:projects task)
                          "goal" (:goals task))
        task-category-ids (set (map :id task-categories))]
    [:div.tag-selector
     [:select
      {:value ""
       :on-change (fn [e]
                    (let [category-id (js/parseInt (-> e .-target .-value))]
                      (when (pos? category-id)
                        (state/categorize-task (:id task) category-type category-id))))}
      [:option {:value ""} (str "+ " label)]
      (for [entity entities
            :when (not (contains? task-category-ids (:id entity)))]
        ^{:key (:id entity)}
        [:option {:value (:id entity)} (:name entity)])]
     (for [category task-categories]
       ^{:key (str category-type "-" (:id category))}
       [:span.tag
        {:class category-type}
        (:name category)
        [:button.remove-tag
         {:on-click #(state/uncategorize-task (:id task) category-type (:id category))}
         "x"]])]))

(defn task-edit-form [task]
  (let [title (r/atom (:title task))
        description (r/atom (or (:description task) ""))]
    (fn []
      [:div.item-edit-form
       [:input {:type "text"
                :value @title
                :on-change #(reset! title (-> % .-target .-value))
                :placeholder "Title"}]
       [:textarea {:value @description
                   :on-change #(reset! description (-> % .-target .-value))
                   :placeholder "Description (optional)"
                   :rows 3}]
       [:div.edit-buttons
        [:button {:on-click (fn []
                              (state/update-task (:id task) @title @description
                                                 #(state/clear-editing)))}
         "Save"]
        [:button.cancel {:on-click #(state/clear-editing)}
         "Cancel"]]])))

(defn task-categories-readonly [task]
  (let [all-categories (concat
                         (map #(assoc % :type "person") (:people task))
                         (map #(assoc % :type "place") (:places task))
                         (map #(assoc % :type "project") (:projects task))
                         (map #(assoc % :type "goal") (:goals task)))]
    (when (seq all-categories)
      [:div.item-tags-readonly
       (for [category all-categories]
         ^{:key (str (:type category) "-" (:id category))}
         [:span.tag {:class (:type category)} (:name category)])])))

(defn tasks-list []
  (let [{:keys [people places projects goals expanded-task editing-task sort-mode drag-task drag-over-task]} @state/app-state
        tasks (state/filtered-tasks)
        manual-mode? (= sort-mode :manual)]
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
              [:div.item-title (:title task)]
              [:div.item-date (:created_at task)]]
             (if is-expanded
               [:div.item-details
                (when (seq (:description task))
                  [:div.item-description (:description task)])
                [:div.item-tags
                 [category-selector task "person" people "Person"]
                 [category-selector task "place" places "Place"]
                 [category-selector task "project" projects "Project"]
                 [category-selector task "goal" goals "Goal"]]
                [:button.edit-btn {:on-click #(state/set-editing (:id task))}
                 "Edit"]]
               [task-categories-readonly task])])]))]))

(defn app []
  (let [{:keys [auth-required? logged-in? active-tab]} @state/app-state]
    [:div
     (cond
       (nil? auth-required?)
       [:div "Loading..."]

       (and auth-required? (not logged-in?))
       [login-form]

       :else
       [:div
        (when-let [error (:error @state/app-state)]
          [:div.error error])
        [:div.top-bar
         [tabs]]
        (case active-tab
          :today [today-tab]
          :people-places [people-places-tab]
          :projects-goals [projects-goals-tab]
          [:div.main-layout
           [sidebar-filters]
           [:div.main-content
            [:div.tasks-header
             [:h2 "Tasks"]
             [sort-mode-toggle]]
            [search-filter]
            [add-task-form]
            [tasks-list]]])])]))

(defn init []
  (state/fetch-auth-required)
  (state/fetch-people)
  (state/fetch-places)
  (state/fetch-projects)
  (state/fetch-goals)
  (rdom/render [app] (.getElementById js/document "app"))
  (when (:logged-in? @state/app-state)
    (state/fetch-tasks)))
