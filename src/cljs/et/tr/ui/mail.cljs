(ns et.tr.ui.mail
  (:require [et.tr.ui.state :as state]
            [et.tr.i18n :refer [t]]))

(defn- format-message-datetime [date-str]
  (when date-str
    (let [date (js/Date. date-str)]
      (str (.toLocaleDateString date js/undefined #js {:year "numeric" :month "short" :day "numeric"})
           ", "
           (.toLocaleTimeString date js/undefined #js {:hour "2-digit" :minute "2-digit"})))))

(defn- mail-message-item [message]
  (let [{:keys [id sender title description created_at done]} message
        expanded-id (:mail-page/expanded-message @state/app-state)
        expanded? (= expanded-id id)]
    [:li {:class (when expanded? "expanded")}
     [:div.item-header {:on-click #(state/set-expanded-message (when-not expanded? id))}
      [:span.item-title
       [:span.mail-sender sender]
       [:span.mail-title title]]
      [:span.item-date (format-message-datetime created_at)]]
     (when expanded?
       [:div.item-details
        (when (not (empty? description))
          [:div.item-description description])
        [:div.item-actions
         (if (= done 1)
           [:button.undone-btn {:on-click #(state/set-message-done id false)}
            (t :mail/set-unarchived)]
           [:button.done-btn {:on-click #(state/set-message-done id true)}
            (t :mail/archive)])
         [:button.delete-btn {:on-click #(state/set-confirm-delete-message message)}
          (t :task/delete)]]])]))

(defn- mail-sort-toggle []
  (let [sort-mode (:mail-page/sort-mode @state/app-state)]
    [:div.sort-toggle.toggle-group
     [:button {:class (when (= sort-mode :recent) "active")
               :on-click #(state/set-mail-sort-mode :recent)}
      (t :mail/sort-recent)]
     [:button {:class (when (= sort-mode :done) "active")
               :on-click #(state/set-mail-sort-mode :done)}
      (t :mail/sort-archived)]]))

(defn mail-page []
  (let [messages (:messages @state/app-state)]
    [:div.mail-page
     [:div.tasks-header
      [:h2 (t :nav/mail)]
      [mail-sort-toggle]]
     (if (empty? messages)
       [:p.empty-message (t :mail/no-messages)]
       [:ul.items
        (for [message messages]
          ^{:key (:id message)}
          [mail-message-item message])])]))
