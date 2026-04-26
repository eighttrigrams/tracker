(ns et.tr.ui.views.users
  (:require [reagent.core :as r]
            [et.tr.ui.state :as state]
            [et.tr.i18n :refer [t]]))

(defn add-user-form []
  (let [username (r/atom "")
        password (r/atom "")
        machine? (r/atom false)
        target-id (r/atom nil)
        reset-form (fn []
                     (reset! username "")
                     (reset! password "")
                     (reset! machine? false)
                     (reset! target-id nil))
        submit (fn []
                 (state/add-user @username @password
                                 (when @machine? @target-id)
                                 reset-form))]
    (fn []
      (let [eligible-targets (->> (:users @state/*app-state)
                                  (remove :is_machine_user))]
        [:div.add-user-form
         [:input {:type "text"
                  :auto-complete "off"
                  :placeholder (t :auth/username)
                  :value @username
                  :on-change #(reset! username (-> % .-target .-value))}]
         [:input {:type "password"
                  :placeholder (t :auth/password)
                  :value @password
                  :on-change #(reset! password (-> % .-target .-value))
                  :on-key-down #(when (= (.-key %) "Enter") (submit))}]
         [:label.machine-user-checkbox
          [:input {:type "checkbox"
                   :checked @machine?
                   :on-change (fn [e]
                                (let [v (-> e .-target .-checked)]
                                  (reset! machine? v)
                                  (when-not v (reset! target-id nil))))}]
          (t :users/machine-user)]
         (when @machine?
           [:select {:value (or @target-id "")
                     :on-change #(let [v (-> % .-target .-value)]
                                   (reset! target-id (when (seq v) (js/parseInt v))))}
            [:option {:value ""} (str "— " (t :users/machine-target) " —")]
            (for [u eligible-targets]
              ^{:key (:id u)}
              [:option {:value (:id u)} (:username u)])])
         [:button {:on-click submit
                   :disabled (or (and @machine? (nil? @target-id))
                                 (empty? @username)
                                 (empty? @password))}
          (t :users/add-button)]]))))

(defn users-tab []
  (let [{:keys [users]} @state/*app-state]
    [:div.manage-tab
     [:div.manage-section
      [:h3 (t :users/title)]
      [add-user-form]
      [:ul.entity-list.user-list
       (doall
        (for [user users]
          ^{:key (:id user)}
          [:li
           [:span.username (:username user)]
           (when (:is_machine_user user)
             [:span.machine-user-badge (t :users/machine-badge)])
           [:button.delete-user-btn
            {:on-click #(state/set-confirm-delete-user user)}
            (t :task/delete)]]))]]]))
