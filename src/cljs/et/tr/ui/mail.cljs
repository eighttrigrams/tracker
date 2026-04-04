(ns et.tr.ui.mail
  (:require [et.tr.ui.state :as state]
            [et.tr.ui.state.mail :as mail-state]
            [et.tr.i18n :refer [t]]
            [reagent.core :as r]
            [clojure.string :as str]
            [et.tr.ui.components.task-item :refer [markdown]]
            [et.tr.ui.date :as date]))

(defn- first-url [& texts]
  (some (fn [text]
          (when (string? text)
            (first (re-seq #"https?://[^\s]+" text))))
        texts))

(defn- first-youtube-video-id [& texts]
  (some (fn [text]
          (when (string? text)
            (or (second (re-find #"(?:youtube\.com/watch[^\s]*[?&]v=)([^&\s]+)" text))
                (second (re-find #"youtube\.com/shorts/([^?/\s]+)" text))
                (second (re-find #"youtu\.be/([^?/\s]+)" text)))))
        texts))

(defn- youtube-embed [video-id]
  [:div.youtube-preview
   [:iframe
    {:width "420"
     :height "315"
     :src (str "https://www.youtube.com/embed/" video-id)
     :allowFullScreen true
     :frameBorder "0"}]])

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

(defn- mail-message-header [{:keys [id sender title created_at]} expanded?]
  [:div.item-header {:on-click #(state/set-expanded-message (when-not expanded? id))}
   [:span.item-title
    [:span.mail-sender {:on-click (fn [e]
                                    (.stopPropagation e)
                                    (if (and (.-shiftKey e)
                                             (nil? (:sender-filter @mail-state/*mail-page-state)))
                                      (state/toggle-excluded-sender sender)
                                      (state/set-mail-sender-filter sender)))}
     sender]
    [:span.mail-title title]
    (when expanded?
      [:<>
       [:button.edit-icon {:on-click (fn [e]
                                       (.stopPropagation e)
                                       (state/set-editing-message id))}
        "✎"]
       [:button.copy-icon {:on-click (fn [e]
                                       (.stopPropagation e)
                                       (.writeText js/navigator.clipboard title))}
        "⧉"]])]
   [:span.item-date {:data-tooltip (some-> created_at (.substring 0 10) date/get-day-name)}
    (format-message-datetime created_at)]])

(defn- archive-button-with-dropdown [{:keys [id title description done] :as _message}]
  (let [url (first-url title description)
        dropdown-open? (= id (:message-dropdown-open @mail-state/*mail-page-state))]
    (if (= done 1)
      [:button.undone-btn {:on-click #(state/set-message-done id false)}
       (t :mail/set-unarchived)]
      [:div.combined-button-wrapper
       [:button.combined-main-btn.done
        {:on-click #(state/set-message-done id true)}
        (t :mail/archive)]
       [:button.combined-dropdown-btn.done
        {:on-click #(state/set-message-dropdown-open (when-not dropdown-open? id))}
        "▼"]
       (when dropdown-open?
         [:div.task-dropdown-menu
          (when url
            [:button.dropdown-item.convert-to-resource
             {:on-click #(do
                           (state/set-message-dropdown-open nil)
                           (state/convert-message-to-resource id url))}
             (t :mail/convert-to-resource)])
          [:button.dropdown-item.convert-to-task
           {:on-click #(do
                         (state/set-message-dropdown-open nil)
                         (state/convert-message-to-task id))}
           (t :mail/convert-to-task)]])])))

(defn- message-scope-selector [message]
  (let [scope (or (:scope message) "both")]
    [:div.task-scope-selector.toggle-group.compact
     (for [s ["private" "both" "work"]]
       ^{:key s}
       [:button.toggle-option
        {:class (when (= scope s) "active")
         :on-click (fn [e]
                     (.stopPropagation e)
                     (state/set-message-scope (:id message) s))}
        s])]))

(defn- message-importance-selector [message]
  (let [importance (or (:importance message) "normal")]
    [:div.task-importance-selector.toggle-group.compact
     (for [[level label] [["normal" "○"] ["important" "★"] ["critical" "★★"]]]
       ^{:key level}
       [:button.toggle-option
        {:class (str level (when (= importance level) " active"))
         :on-click (fn [e]
                     (.stopPropagation e)
                     (state/set-message-importance (:id message) level))}
        label])]))

(defn- message-urgency-selector [message]
  (let [urgency (or (:urgency message) "default")]
    [:div.task-urgency-selector.toggle-group.compact
     (for [[level label] [["default" "—"] ["urgent" "🚨"] ["superurgent" "🚨🚨"]]]
       ^{:key level}
       [:button.toggle-option
        {:class (str level (when (= urgency level) " active"))
         :on-click (fn [e]
                     (.stopPropagation e)
                     (state/set-message-urgency (:id message) level))}
        label])]))

(defn- youtube-message-actions [{:keys [id title description] :as message}]
  (let [url (first-url title description)]
    [:div.item-actions
     (when url
       [:button.combined-main-btn.done
        {:on-click #(state/convert-message-to-resource id url)}
        (t :mail/convert-to-resource)])
     [message-scope-selector message]
     [message-importance-selector message]
     [message-urgency-selector message]
     [:button.delete-btn {:on-click #(state/set-confirm-delete-message message)}
      (t :task/delete)]]))

(defn- mail-message-actions [message next-message-id]
  (if (= (:sender message) "YouTube")
    [youtube-message-actions message]
    (let [dropdown-open? (= (:id message) (:message-action-dropdown-open @mail-state/*mail-page-state))]
      [:div.item-actions
       [archive-button-with-dropdown message]
       [message-scope-selector message]
       [message-importance-selector message]
       [message-urgency-selector message]
       [:div.combined-button-wrapper
        [:button.delete-btn {:on-click #(state/set-confirm-delete-message message)}
         (t :task/delete)]
        [:button.combined-dropdown-btn.delete-btn
         {:on-click #(state/set-message-action-dropdown-open (when-not dropdown-open? (:id message)))}
         "▼"]
        (when dropdown-open?
          [:div.task-dropdown-menu
           (when next-message-id
             [:button.dropdown-item
              {:on-click #(do
                            (state/set-message-action-dropdown-open nil)
                            (state/merge-message-with-below (:id message) next-message-id))}
              (t :mail/merge-with-below)])])]])))

(defn- mail-message-expanded-content [{:keys [title description annotation type] :as message} editing? next-message-id]
  [:div.item-details
   (when-let [video-id (first-youtube-video-id title description)]
     [youtube-embed video-id])
   (when (seq description)
     [:div.description-wrapper
      (if (= type "markdown")
        [markdown description]
        [:div.item-description description])
      [:button.copy-icon {:on-click #(.writeText js/navigator.clipboard description)} "⧉"]])
   (if editing?
     [message-annotation-edit-form message]
     [:<>
      (when (seq annotation)
        [markdown annotation])
      [mail-message-actions message next-message-id]])])

(defn- archive-checkbox [message archiving?]
  [:div.archive-checkbox-wrapper
   [:input.archive-checkbox
    {:type "checkbox"
     :checked @archiving?
     :on-click #(.stopPropagation %)
     :on-change (fn [_]
                  (reset! archiving? true)
                  (js/setTimeout #(state/set-message-done (:id message) true) 1000))}]])

(defn- mail-message-item [_message _expanded-id _editing-id _sort-mode _next-message-id]
  (let [archiving? (r/atom false)]
    (fn [message expanded-id editing-id sort-mode next-message-id]
      (let [{:keys [id]} message
            expanded? (= expanded-id id)
            editing? (= editing-id id)
            show-checkbox? (and (not expanded?) (#{:recent :reverse} sort-mode) (not= (:sender message) "YouTube"))]
        [:li {:class (str (when expanded? "expanded")
                          (when @archiving? " archiving-out"))}
         [:div.mail-item-row
          (when show-checkbox?
            [archive-checkbox message archiving?])
          [:div.mail-item-content
           [mail-message-header message expanded?]
           (when expanded?
             [mail-message-expanded-content message editing? next-message-id])]]]))))

(defn- mail-sender-filter-badge []
  (let [sender-filter (:sender-filter @mail-state/*mail-page-state)
        excluded-senders (:excluded-senders @mail-state/*mail-page-state)]
    (when (or sender-filter (seq excluded-senders))
      [:div.mail-sender-filter
       (when sender-filter
         [:span.filter-item-label.included
          sender-filter
          [:button.remove-item {:on-click #(state/clear-mail-sender-filter)} "x"]])
       (for [sender excluded-senders]
         ^{:key sender}
         [:span.filter-item-label.excluded
          sender
          [:button.remove-item {:on-click #(state/clear-excluded-sender sender)} "x"]])
       (when (>= (count excluded-senders) 2)
         [:button.remove-all-filters {:on-click #(state/clear-all-mail-filters)} "x"])])))

(defn- importance-filter-toggle []
  (let [importance-filter (:importance-filter @mail-state/*mail-page-state)]
    [:div.importance-filter-toggle.toggle-group
     [:button {:class (when (nil? importance-filter) "active")
               :on-click #(state/set-message-importance-filter nil)
               :title (t :importance/filter-off)}
      "○"]
     [:button {:class (str "important" (when (= importance-filter :important) " active"))
               :on-click #(state/set-message-importance-filter :important)
               :title (t :importance/filter-important)}
      "★"]
     [:button {:class (str "critical" (when (= importance-filter :critical) " active"))
               :on-click #(state/set-message-importance-filter :critical)
               :title (t :importance/filter-critical)}
      "★★"]]))

(defn- urgency-filter-toggle []
  (let [urgency-filter (:urgency-filter @mail-state/*mail-page-state)]
    [:div.urgency-filter-toggle.toggle-group
     [:button {:class (when (nil? urgency-filter) "active")
               :on-click #(state/set-message-urgency-filter nil)
               :title (t :urgency/filter-off)}
      "—"]
     [:button {:class (str "urgent" (when (= urgency-filter :urgent) " active"))
               :on-click #(state/set-message-urgency-filter :urgent)
               :title (t :urgency/filter-urgent)}
      "🚨"]
     [:button {:class (str "superurgent" (when (= urgency-filter :superurgent) " active"))
               :on-click #(state/set-message-urgency-filter :superurgent)
               :title (t :urgency/filter-superurgent)}
      "🚨🚨"]]))

(defn- mail-sort-toggle []
  (let [sort-mode (:sort-mode @mail-state/*mail-page-state)]
    [:div.sort-toggle.toggle-group
     [:button {:class (when (= sort-mode :recent) "active")
               :on-click #(state/set-mail-sort-mode :recent)}
      (t :mail/sort-recent)]
     [:button {:class (when (= sort-mode :reverse) "active")
               :on-click #(state/set-mail-sort-mode :reverse)}
      (t :mail/sort-reverse)]
     [:button {:class (when (= sort-mode :done) "active")
               :on-click #(state/set-mail-sort-mode :done)}
      (t :mail/sort-archived)]]))

(defn- any-filter-active? []
  (let [{:keys [sender-filter excluded-senders importance-filter urgency-filter]} @mail-state/*mail-page-state]
    (or sender-filter (seq excluded-senders) importance-filter urgency-filter)))

(defn- mail-add-form []
  (let [input-val (r/atom "")]
    (fn []
      (let [disabled? (or (str/blank? @input-val) (any-filter-active?))]
        [:div.mail-add-form
         [:input {:type "text"
                  :value @input-val
                  :placeholder (t :mail/add-placeholder)
                  :on-change #(reset! input-val (-> % .-target .-value))
                  :on-key-down #(when (and (= (.-key %) "Enter") (not disabled?))
                                  (state/add-message @input-val (fn [] (reset! input-val ""))))}]
         [:button {:disabled disabled?
                   :on-click #(when-not disabled?
                                (state/add-message @input-val (fn [] (reset! input-val ""))))}
          (t :tasks/add-button)]]))))

(defn mail-page []
  (let [{:keys [messages]} @state/*app-state
        {:keys [expanded-message editing-message sort-mode]} @mail-state/*mail-page-state]
    [:div.mail-page
     [:div.tasks-header
      [:h2 (t :mail/heading)]
      [importance-filter-toggle]
      [urgency-filter-toggle]
      [mail-sort-toggle]]
     (when (= sort-mode :recent)
       [mail-add-form])
     (when (and (= sort-mode :done) (seq messages))
       [:div.mail-delete-all-archived
        [:button.delete-btn {:on-click #(state/set-confirm-delete-all-archived true)}
         (t :mail/delete-all-archived)]])
     [mail-sender-filter-badge]
     (if (empty? messages)
       [:p.empty-message (t :mail/no-messages)]
       (let [indexed (map-indexed vector messages)]
         [:ul.items
          (for [[idx message] indexed]
            (let [next-message-id (some-> (get messages (inc idx)) :id)]
              ^{:key (:id message)}
              [mail-message-item message expanded-message editing-message sort-mode next-message-id]))]))]))
