(ns et.tr.ui.views.settings
  (:require [et.tr.ui.state :as state]
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

(defn settings-tab []
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
         [language-selector])]
      [:div.manage-section.settings-section
       [:h3 (t :settings/data)]
       [:div.settings-item
        [:button.export-btn {:on-click #(state/export-data)}
         (t :settings/export-data)]]]]
     [:hr.settings-separator]
     [:div.shortcuts-section
      [:h3 (t :settings/shortcuts)]
      [:div.shortcuts-subsection
       [:h4 (t :settings/shortcuts-navigation)]
       [:div.shortcuts-list
        [:div.shortcut-item
         [:span.shortcut-key "Shift+←"]
         [:span.shortcut-desc (t :settings/shortcut-arrow-left)]]
        [:div.shortcut-item
         [:span.shortcut-key "Shift+→"]
         [:span.shortcut-desc (t :settings/shortcut-arrow-right)]]
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
         [:span.shortcut-desc (t :settings/shortcut-add-task)]]]]]]))
