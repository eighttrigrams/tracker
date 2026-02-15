(ns et.tr.ui.modals
  (:require [reagent.core :as r]
            [et.tr.ui.state :as state]
            [et.tr.ui.state.mail :as mail-state]
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

(defn- make-confirm-delete-modal [{:keys [state-atom state-key header-i18n confirm-i18n title-key clear-fn delete-fn]}]
  (fn []
    (when-let [entity (state-key @state-atom)]
      [generic-confirm-modal
       {:header (t header-i18n)
        :body-paragraphs [{:text (t confirm-i18n)}
                          {:text (title-key entity) :class "task-title"}]
        :on-cancel clear-fn
        :on-confirm #(delete-fn (:id entity))}])))

(def confirm-delete-modal
  (make-confirm-delete-modal
   {:state-atom state/*app-state
    :state-key :confirm-delete-task
    :header-i18n :modal/delete-task
    :confirm-i18n :modal/delete-task-confirm
    :title-key :title
    :clear-fn state/clear-confirm-delete
    :delete-fn state/delete-task}))

(defn confirm-delete-user-modal []
  (let [confirmation-input (r/atom "")]
    (fn []
      (when-let [user (:confirm-delete-user @state/*app-state)]
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
  (when-let [{:keys [type category]} (:confirm-delete-category @state/*app-state)]
    (let [type-label (case type
                       "person" (t :category/person)
                       "place" (t :category/place)
                       "project" (t :category/project)
                       "goal" (t :category/goal)
                       type)
          delete-fn (case type
                      "person" state/delete-person
                      "place" state/delete-place
                      "project" state/delete-project
                      "goal" state/delete-goal)]
      [generic-confirm-modal
       {:header (tf :modal/delete-category type-label)
        :body-paragraphs [{:text (tf :modal/delete-category-confirm type-label)}
                          {:text (:name category) :class "task-title"}
                          {:text (tf :modal/delete-category-warning type-label) :class "warning"}]
        :on-cancel state/clear-confirm-delete-category
        :on-confirm #(delete-fn (:id category))}])))

(def confirm-delete-message-modal
  (make-confirm-delete-modal
   {:state-atom mail-state/*mail-page-state
    :state-key :confirm-delete-message
    :header-i18n :modal/delete-message
    :confirm-i18n :modal/delete-message-confirm
    :title-key :title
    :clear-fn state/clear-confirm-delete-message
    :delete-fn state/delete-message}))

(defn category-tag-item [category-type id name selected? toggle-fn]
  [:span.tag.selectable
   {:class (str category-type (when selected? " selected"))
    :on-click #(toggle-fn category-type id)}
   name
   (when selected? [:span.check " ✓"])])

(defn- category-group [state-key category-type selected-set i18n-label-key]
  (when (seq (state-key @state/*app-state))
    [:div.category-group
     [:label (str (t i18n-label-key) ":")]
     [:div.category-tags
      (doall
       (for [item (state-key @state/*app-state)]
         ^{:key (:id item)}
         [category-tag-item category-type (:id item) (:name item)
          (contains? selected-set (:id item))
          state/update-pending-category]))]]))

(defn pending-task-modal []
  (when-let [{:keys [title categories]} (:pending-new-task @state/*app-state)]
    (let [{:keys [people places projects goals]} categories
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
         [category-group :people state/CATEGORY-TYPE-PERSON selected-people :category/people]
         [category-group :places state/CATEGORY-TYPE-PLACE selected-places :category/places]
         [category-group :projects state/CATEGORY-TYPE-PROJECT selected-projects :category/projects]
         [category-group :goals state/CATEGORY-TYPE-GOAL selected-goals :category/goals]]
        [:div.modal-footer
         [:button.cancel {:on-click #(state/clear-pending-new-task)} (t :modal/cancel)]
         [:button.confirm {:on-click #(state/confirm-pending-new-task)} (t :modal/add-task)]]]])))
