(ns et.tr.ui.modals
  (:require [reagent.core :as r]
            [et.tr.ui.state :as state]
            [et.tr.i18n :refer [t tf]]))

(defn- generic-confirm-modal
  [{:keys [header body-paragraphs on-cancel on-confirm]}]
  [:div.modal-overlay {:on-click on-cancel}
   [:div.modal {:on-click #(.stopPropagation %)}
    [:div.modal-header header]
    [:div.modal-body
     (for [[idx p] (map-indexed vector body-paragraphs)]
       ^{:key idx}
       (if (:class p)
         [:p {:class (:class p)} (:text p)]
         [:p (:text p)]))]
    [:div.modal-footer
     [:button.cancel {:on-click on-cancel} (t :modal/cancel)]
     [:button.confirm-delete {:on-click on-confirm} (t :modal/delete)]]]])

(defn confirm-delete-modal []
  (when-let [task (:confirm-delete-task @state/app-state)]
    [generic-confirm-modal
     {:header (t :modal/delete-task)
      :body-paragraphs [{:text (t :modal/delete-task-confirm)}
                        {:text (:title task) :class "task-title"}]
      :on-cancel state/clear-confirm-delete
      :on-confirm #(state/delete-task (:id task))}]))

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
      [generic-confirm-modal
       {:header (tf :modal/delete-category type-label)
        :body-paragraphs [{:text (tf :modal/delete-category-confirm type-label)}
                          {:text (:name category) :class "task-title"}
                          {:text (tf :modal/delete-category-warning type-label) :class "warning"}]
        :on-cancel state/clear-confirm-delete-category
        :on-confirm #(delete-fn (:id category))}])))

(defn confirm-delete-message-modal []
  (when-let [message (:confirm-delete-message @state/app-state)]
    [generic-confirm-modal
     {:header (t :modal/delete-message)
      :body-paragraphs [{:text (t :modal/delete-message-confirm)}
                        {:text (:title message) :class "task-title"}]
      :on-cancel state/clear-confirm-delete-message
      :on-confirm #(state/delete-message (:id message))}]))

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
             (doall
              (for [p (:people @state/app-state)]
                ^{:key (:id p)}
                [category-tag-item "person" (:id p) (:name p)
                 (contains? selected-people (:id p))
                 state/update-pending-category]))]])
         (when (seq (:places @state/app-state))
           [:div.category-group
            [:label (str (t :category/places) ":")]
            [:div.category-tags
             (doall
              (for [p (:places @state/app-state)]
                ^{:key (:id p)}
                [category-tag-item "place" (:id p) (:name p)
                 (contains? selected-places (:id p))
                 state/update-pending-category]))]])
         (when (seq (:projects @state/app-state))
           [:div.category-group
            [:label (str (t :category/projects) ":")]
            [:div.category-tags
             (doall
              (for [p (:projects @state/app-state)]
                ^{:key (:id p)}
                [category-tag-item "project" (:id p) (:name p)
                 (contains? selected-projects (:id p))
                 state/update-pending-category]))]])
         (when (seq (:goals @state/app-state))
           [:div.category-group
            [:label (str (t :category/goals) ":")]
            [:div.category-tags
             (doall
              (for [g (:goals @state/app-state)]
                ^{:key (:id g)}
                [category-tag-item "goal" (:id g) (:name g)
                 (contains? selected-goals (:id g))
                 state/update-pending-category]))]])]
        [:div.modal-footer
         [:button.cancel {:on-click #(state/clear-pending-new-task)} (t :modal/cancel)]
         [:button.confirm {:on-click #(state/confirm-pending-new-task)} (t :modal/add-task)]]]])))
