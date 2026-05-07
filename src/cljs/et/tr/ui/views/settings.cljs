(ns et.tr.ui.views.settings
  (:require [reagent.core :as r]
            [cljs.pprint]
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

(defn history-section []
  (r/create-class
   {:component-did-mount
    (fn [] (state/fetch-events))
    :reagent-render
    (fn []
      (let [events (:events @state/*app-state)]
        [:div.manage-section.settings-section
         [:h3 (t :settings/history)]
         (if (empty? events)
           [:div.settings-item.history-empty (t :history/no-events)]
           [:ul.history-list
            (for [ev events]
              ^{:key (:id ev)}
              [event-row ev])])]))}))

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
         [language-selector])
       (when-not is-admin
         [vim-keys-toggle])]
      [:div.manage-section.settings-section
       [:h3 (t :settings/data)]
       [:div.settings-item
        [:button.export-btn {:on-click #(state/export-data)}
         (t :settings/export-data)]]]
      [history-section]]
     [:hr.settings-separator]
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
         [:span.shortcut-desc (t :settings/shortcut-add-task)]]]]]]))
