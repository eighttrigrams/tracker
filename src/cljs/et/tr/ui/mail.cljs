(ns et.tr.ui.mail
  (:require [et.tr.ui.state :as state]
            [et.tr.i18n :refer [t]]
            [reagent.core :as r]))

(defn- format-message-datetime [date-str]
  (when date-str
    (let [date (js/Date. date-str)]
      (str (.toLocaleDateString date js/undefined #js {:year "numeric" :month "short" :day "numeric"})
           ", "
           (.toLocaleTimeString date js/undefined #js {:hour "2-digit" :minute "2-digit"})))))

(defn- message-annotation-edit-form [message]
  (let [annotation-val (r/atom (or (:annotation message) ""))]
    (fn []
      [:div.item-edit-form
       [:textarea {:value @annotation-val
                   :on-change #(reset! annotation-val (-> % .-target .-value))
                   :placeholder (t :mail/annotation-placeholder)
                   :rows 3}]
       [:div.edit-buttons
        [:button {:on-click #(state/update-message-annotation (:id message) @annotation-val)}
         (t :task/save)]
        [:button.cancel {:on-click #(state/clear-editing-message)}
         (t :task/cancel)]]])))

(defn- mail-message-item [message]
  (let [{:keys [id sender title description annotation created_at done]} message
        expanded-id (:mail-page/expanded-message @state/app-state)
        editing-id (:mail-page/editing-message @state/app-state)
        expanded? (= expanded-id id)
        editing? (= editing-id id)]
    [:li {:class (when expanded? "expanded")}
     [:div.item-header {:on-click #(state/set-expanded-message (when-not expanded? id))}
      [:span.item-title
       [:span.mail-sender {:on-click (fn [e]
                                       (.stopPropagation e)
                                       (state/set-mail-sender-filter sender))}
        sender]
       [:span.mail-title title]
       (when expanded?
         [:button.edit-icon {:on-click (fn [e]
                                         (.stopPropagation e)
                                         (state/set-editing-message id))}
          "✎"])]
      [:span.item-date (format-message-datetime created_at)]]
     (when expanded?
       [:div.item-details
        (when (seq description)
          [:div.item-description description])
        (if editing?
          [message-annotation-edit-form message]
          (when (seq annotation)
            [:div.item-annotation annotation]))
        [:div.item-actions
         (if (= done 1)
           [:button.undone-btn {:on-click #(state/set-message-done id false)}
            (t :mail/set-unarchived)]
           [:button.done-btn {:on-click #(state/set-message-done id true)}
            (t :mail/archive)])
         [:button.delete-btn {:on-click #(state/set-confirm-delete-message message)}
          (t :task/delete)]]])]))

(defn- mail-sender-filter-badge []
  (let [sender-filter (:mail-page/sender-filter @state/app-state)]
    (when sender-filter
      [:div.mail-sender-filter
       [:span.filter-item-label.included
        sender-filter
        [:button.remove-item {:on-click #(state/clear-mail-sender-filter)} "x"]]])))

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
     [mail-sender-filter-badge]
     (if (empty? messages)
       [:p.empty-message (t :mail/no-messages)]
       [:ul.items
        (for [message messages]
          ^{:key (:id message)}
          [mail-message-item message])])]))
