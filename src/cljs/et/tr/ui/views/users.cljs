(ns et.tr.ui.views.users
  (:require [reagent.core :as r]
            [et.tr.ui.state :as state]
            [et.tr.i18n :refer [t]]))

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
           [:button.delete-user-btn
            {:on-click #(state/set-confirm-delete-user user)}
            (t :task/delete)]]))]]]))
