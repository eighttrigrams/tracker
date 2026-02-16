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

(defn- archive-button-with-dropdown [{:keys [id title description done] :as message}]
  (let [url (first-url title description)
        dropdown-open? (= id (:message-dropdown-open @mail-state/*mail-page-state))]
    (if (and url (not= done 1))
      [:div.combined-button-wrapper
       [:button.combined-main-btn.done
        {:on-click #(state/set-message-done id true)}
        (t :mail/archive)]
       [:button.combined-dropdown-btn.done
        {:on-click #(state/set-message-dropdown-open (when-not dropdown-open? id))}
        "▼"]
       (when dropdown-open?
         [:div.task-dropdown-menu
          [:button.dropdown-item.convert-to-resource
           {:on-click #(do
                         (state/set-message-dropdown-open nil)
                         (state/convert-message-to-resource id url))}
           (t :mail/convert-to-resource)]])]
      (if (= done 1)
        [:button.undone-btn {:on-click #(state/set-message-done id false)}
         (t :mail/set-unarchived)]
        [:button.done-btn {:on-click #(state/set-message-done id true)}
         (t :mail/archive)]))))

(defn- mail-message-actions [message]
  [:div.item-actions
   [archive-button-with-dropdown message]
   [:div.combined-button-wrapper
    [:button.delete-btn {:on-click #(state/set-confirm-delete-message message)}
     (t :task/delete)]]])

(defn- mail-message-expanded-content [{:keys [title description annotation type] :as message} editing?]
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
      [mail-message-actions message]])])

(defn- archive-checkbox [message archiving?]
  [:div.archive-checkbox-wrapper
   [:input.archive-checkbox
    {:type "checkbox"
     :checked @archiving?
     :on-click #(.stopPropagation %)
     :on-change (fn [_]
                  (reset! archiving? true)
                  (js/setTimeout #(state/set-message-done (:id message) true) 1000))}]])

(defn- mail-message-item [_message _expanded-id _editing-id _sort-mode]
  (let [archiving? (r/atom false)]
    (fn [message expanded-id editing-id sort-mode]
      (let [{:keys [id]} message
            expanded? (= expanded-id id)
            editing? (= editing-id id)
            show-checkbox? (and (not expanded?) (#{:recent :reverse} sort-mode))]
        [:li {:class (str (when expanded? "expanded")
                          (when @archiving? " archiving-out"))}
         [:div.mail-item-row
          (when show-checkbox?
            [archive-checkbox message archiving?])
          [:div.mail-item-content
           [mail-message-header message expanded?]
           (when expanded?
             [mail-message-expanded-content message editing?])]]]))))

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

(defn- mail-add-form []
  (let [input-val (r/atom "")]
    (fn []
      [:div.mail-add-form
       [:input {:type "text"
                :value @input-val
                :placeholder (t :mail/add-placeholder)
                :on-change #(reset! input-val (-> % .-target .-value))
                :on-key-down #(when (and (= (.-key %) "Enter")
                                         (not (str/blank? @input-val)))
                                (state/add-message @input-val (fn [] (reset! input-val ""))))}]
       [:button {:disabled (str/blank? @input-val)
                 :on-click #(when-not (str/blank? @input-val)
                              (state/add-message @input-val (fn [] (reset! input-val ""))))}
        (t :tasks/add-button)]])))

(defn mail-page []
  (let [{:keys [messages]} @state/*app-state
        {:keys [expanded-message editing-message sort-mode]} @mail-state/*mail-page-state]
    [:div.mail-page
     [:div.tasks-header
      [:h2 (t :nav/mail)]
      [mail-sort-toggle]]
     (when (= sort-mode :recent)
       [mail-add-form])
     [mail-sender-filter-badge]
     (if (empty? messages)
       [:p.empty-message (t :mail/no-messages)]
       [:ul.items
        (for [message messages]
          ^{:key (:id message)}
          [mail-message-item message expanded-message editing-message sort-mode])])]))
