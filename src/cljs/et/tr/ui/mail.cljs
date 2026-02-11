(ns et.tr.ui.mail
  (:require [et.tr.ui.state :as state]
            [et.tr.ui.state.mail :as mail-state]
            [et.tr.i18n :refer [t]]
            [reagent.core :as r]
            [clojure.string :as str]))

(defn- youtube-video-id [url]
  (when (string? url)
    (cond
      (str/includes? url "youtube.com/watch")
      (second (re-find #"[?&]v=([^&\s]+)" url))

      (str/includes? url "youtube.com/shorts/")
      (second (re-find #"shorts/([^?/\s]+)" url))

      (str/includes? url "youtu.be/")
      (second (re-find #"youtu\.be/([^?/\s]+)" url))

      :else nil)))

(defn- youtube-preview [title]
  (when-let [video-id (youtube-video-id title)]
    [:div.youtube-preview
     [:img.youtube-thumbnail
      {:src (str "https://img.youtube.com/vi/" video-id "/hqdefault.jpg")
       :alt "YouTube thumbnail"}]
     [:iframe
      {:width "420"
       :height "315"
       :src (str "https://www.youtube.com/embed/" video-id)
       :allowFullScreen true
       :frameBorder "0"}]]))

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
                                    (state/set-mail-sender-filter sender))}
     sender]
    [:span.mail-title title]
    (when expanded?
      [:button.edit-icon {:on-click (fn [e]
                                      (.stopPropagation e)
                                      (state/set-editing-message id))}
       "✎"])]
   [:span.item-date (format-message-datetime created_at)]])

(defn- mail-message-actions [{:keys [id done] :as message}]
  [:div.item-actions
   (if (= done 1)
     [:button.undone-btn {:on-click #(state/set-message-done id false)}
      (t :mail/set-unarchived)]
     [:button.done-btn {:on-click #(state/set-message-done id true)}
      (t :mail/archive)])
   [:button.delete-btn {:on-click #(state/set-confirm-delete-message message)}
    (t :task/delete)]])

(defn- mail-message-expanded-content [{:keys [title description annotation] :as message} editing?]
  [:div.item-details
   [youtube-preview title]
   (when (seq description)
     [:div.item-description description])
   (if editing?
     [message-annotation-edit-form message]
     [:<>
      (when (seq annotation)
        [:div.item-annotation annotation])
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
  (let [sender-filter (:sender-filter @mail-state/*mail-page-state)]
    (when sender-filter
      [:div.mail-sender-filter
       [:span.filter-item-label.included
        sender-filter
        [:button.remove-item {:on-click #(state/clear-mail-sender-filter)} "x"]]])))

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
