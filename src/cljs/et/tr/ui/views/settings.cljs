(ns et.tr.ui.views.settings
  (:require [reagent.core :as r]
            [cljs.pprint]
            [clojure.string :as str]
            [et.tr.ui.state :as state]
            [et.tr.i18n :refer [t]]))

(defn language-selector []
  (let [current-user (:current-user @state/*app-state)
        current-lang (or (:language current-user) "en")]
    [:div.settings-item
     [:span.settings-label (t :settings/language)]
     [:select.language-select
      {:value current-lang
       :on-change #(state/update-user-language (-> % .-target .-value))}
      [:option {:value "en"} (t :settings/language-en)]
      [:option {:value "de"} (t :settings/language-de)]
      [:option {:value "pt"} (t :settings/language-pt)]]]))

(defn- vim-keys-toggle []
  (let [current-user (:current-user @state/*app-state)
        enabled (= 1 (:vim_keys current-user))]
    [:div.settings-item
     [:label
      [:input {:type "checkbox"
               :checked enabled
               :on-change #(state/update-vim-keys (not enabled))}]
      (str " " (t :settings/vim-keys))]]))

(defn- add-machine-user-form []
  (let [username   (r/atom "")
        password   (r/atom "")
        mail-only? (r/atom false)
        reset-form (fn []
                     (reset! username "")
                     (reset! password "")
                     (reset! mail-only? false))
        submit (fn []
                 (when (and (seq @username) (seq @password))
                   (state/add-my-machine-user
                     @username @password @mail-only? reset-form)))]
    (fn []
      [:div.add-machine-user-form
       [:input {:type "text"
                :auto-complete "off"
                :placeholder (t :machine-users/username)
                :value @username
                :on-change #(reset! username (-> % .-target .-value))}]
       [:input {:type "password"
                :placeholder (t :machine-users/password)
                :value @password
                :on-change #(reset! password (-> % .-target .-value))
                :on-key-down #(when (= (.-key %) "Enter") (submit))}]
       [:label.mail-only-checkbox
        [:input {:type "checkbox"
                 :checked @mail-only?
                 :on-change #(reset! mail-only? (-> % .-target .-checked))}]
        (t :machine-users/mail-only)]
       [:button {:on-click submit
                 :disabled (or (empty? @username) (empty? @password))}
        (t :machine-users/add-button)]])))

(defn- machine-user-row [_user]
  (let [mode         (r/atom :view)
        edit-name    (r/atom "")
        edit-mail?   (r/atom false)
        new-pwd      (r/atom "")
        reset-edit (fn []
                     (reset! mode :view)
                     (reset! edit-name "")
                     (reset! edit-mail? false)
                     (reset! new-pwd ""))
        start-edit (fn [user]
                     (reset! edit-name (:username user))
                     (reset! edit-mail? (boolean (:mail_only user)))
                     (reset! mode :edit))
        start-pwd (fn []
                    (reset! new-pwd "")
                    (reset! mode :pwd))
        save-edit (fn [user]
                    (let [body (cond-> {}
                                 (and (seq (str/trim @edit-name))
                                      (not= @edit-name (:username user)))
                                 (assoc :username (str/trim @edit-name))
                                 (not= @edit-mail? (boolean (:mail_only user)))
                                 (assoc :mail_only @edit-mail?))]
                      (if (seq body)
                        (state/update-my-machine-user (:id user) body reset-edit)
                        (reset-edit))))
        save-pwd (fn [user]
                   (when (seq (str/trim @new-pwd))
                     (state/change-my-machine-user-password
                       (:id user) (str/trim @new-pwd) reset-edit)))]
    (fn [user]
      [:li.machine-user-row
       (case @mode
         :edit
         [:div.machine-user-edit
          [:input {:type "text"
                   :auto-complete "off"
                   :value @edit-name
                   :on-change #(reset! edit-name (-> % .-target .-value))}]
          [:label.mail-only-checkbox
           [:input {:type "checkbox"
                    :checked @edit-mail?
                    :on-change #(reset! edit-mail? (-> % .-target .-checked))}]
           (t :machine-users/mail-only)]
          [:button {:on-click #(save-edit user)} (t :machine-users/save)]
          [:button {:on-click reset-edit} (t :machine-users/cancel)]]

         :pwd
         [:div.machine-user-pwd
          [:input {:type "password"
                   :placeholder (t :machine-users/new-password)
                   :value @new-pwd
                   :on-change #(reset! new-pwd (-> % .-target .-value))
                   :on-key-down #(when (= (.-key %) "Enter") (save-pwd user))}]
          [:button {:on-click #(save-pwd user)
                    :disabled (empty? (str/trim @new-pwd))}
           (t :machine-users/save)]
          [:button {:on-click reset-edit} (t :machine-users/cancel)]]

         ;; :view
         [:div.machine-user-view
          [:span.username (:username user)]
          (when (:mail_only user)
            [:span.mail-only-badge (t :users/mail-only-badge)])
          [:button {:on-click #(start-edit user)} (t :machine-users/rename)]
          [:button {:on-click start-pwd} (t :machine-users/change-password)]
          [:button.delete-user-btn
           {:on-click #(when (js/confirm (t :machine-users/delete-confirm))
                         (state/delete-my-machine-user (:id user)))}
           (t :machine-users/delete)]])])))

(defn- machine-users-section []
  (r/create-class
   {:component-did-mount (fn [] (state/fetch-my-machine-users))
    :reagent-render
    (fn []
      (let [users (:my-machine-users @state/*app-state)]
        [:div.manage-section.settings-section.machine-users-section
         [:h3 (t :settings/machine-users)]
         [:p.muted (t :settings/machine-users-help)]
         [add-machine-user-form]
         (if (empty? users)
           [:p.muted (t :settings/machine-users-empty)]
           [:ul.entity-list.machine-user-list
            (doall
              (for [u users]
                ^{:key (:id u)} [machine-user-row u]))])]))}))

(defn profile-tab []
  (let [current-user (:current-user @state/*app-state)
        is-admin (:is_admin current-user)]
    [:div.settings-page
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
         [language-selector])
       (when-not is-admin
         [vim-keys-toggle])
       [:div.settings-item
        [:button.export-btn {:on-click #(state/export-data)}
         (t :settings/export-data)]]]
      (when-not is-admin
        [machine-users-section])]]))

(defn shortcuts-tab []
  [:div.settings-page
   [:div.shortcuts-section
    [:h3 (t :settings/shortcuts)]
    [:div.shortcuts-subsection
     [:h4 (t :settings/shortcuts-navigation)]
     [:div.shortcuts-list
      [:div.shortcut-item
       [:span.shortcut-key "Option+T"]
       [:span.shortcut-desc (t :settings/shortcut-toggle-today-tasks)]]]]
    [:div.shortcuts-subsection
     [:h4 (t :settings/shortcuts-filters)]
     [:div.shortcuts-list
      [:div.shortcut-item
       [:span.shortcut-key "Option+<n>"]
       [:span.shortcut-desc (t :settings/shortcut-toggle-filter)]]
      [:div.shortcut-item
       [:span.shortcut-key "Option+Esc"]
       [:span.shortcut-desc (t :settings/shortcut-clear-uncollapsed)]]
      [:div.shortcut-item
       [:span.shortcut-key "Enter"]
       [:span.shortcut-desc (t :settings/shortcut-enter-filter)]]]]
    [:div.shortcuts-subsection
     [:h4 (t :settings/shortcuts-tasks)]
     [:div.shortcuts-list
      [:div.shortcut-item
       [:span.shortcut-key "Option+Enter"]
       [:span.shortcut-desc (t :settings/shortcut-add-task)]]]]]])

(defn- summary-line [ev]
  (let [actor (:actor_username ev)
        ent (or (:entity_type ev) "")
        eid (:entity_id ev)
        action (:action ev)
        target (cond-> (str ent (when eid (str " #" eid)))
                 (= "" ent) (str "system"))]
    (str actor " " action " " target)))

(defn- event-row [ev]
  (let [expanded? (r/atom false)]
    (fn [ev]
      [:li.history-item
       {:class (when (:dropped ev) "history-item-dropped")
        :on-click #(swap! expanded? not)}
       [:div.history-row
        [:span.history-ts (or (:ts ev) "")]
        [:span.history-actor
         (:actor_username ev)
         (when (:is_machine ev)
           [:span.history-via " " (t :history/via) " "
            (str "user #" (:parent_user_id ev) " (" (or (:parent_username ev) "?") ")")])]
        [:span.history-summary (summary-line ev)]
        (when (:dropped ev)
          [:span.history-dropped-badge (t :history/dropped-badge)])]
       (when @expanded?
         [:pre.history-payload
          (with-out-str
            (cljs.pprint/pprint
              (-> ev
                  (select-keys [:version :id :ts :entity_type :entity_id :action
                                :actor_user_id :actor_username :is_machine
                                :parent_user_id :parent_username :effective_user_id
                                :dropped :payload])
                  (assoc :payload (:payload ev)))))])])))

(defn history-tab []
  (r/create-class
   {:component-did-mount
    (fn [] (state/fetch-events))
    :reagent-render
    (fn []
      (let [events (:events @state/*app-state)]
        [:div.settings-page
         [:div.history-page
          [:h3.history-heading (t :settings/history)]
          (if (empty? events)
            [:div.history-empty (t :history/no-events)]
            [:ul.items.history-list
             (for [ev events]
               ^{:key (:id ev)}
               [event-row ev])])]]))}))
