(ns et.tr.ui.mail
  (:require [et.tr.ui.state :as state]
            [et.tr.ui.state.mail :as mail-state]
            [et.tr.i18n :refer [t]]
            [reagent.core :as r]
            [clojure.string :as str]
            [et.tr.ui.components.task-item :refer [clampable-description]]
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

(defn- mail-message-inline-title-edit [message]
  (let [value (or (:inline-edit-title @mail-state/*mail-page-state) "")
        commit! (fn []
                  (state/update-message (:id message) value (or (:description message) "")
                    #(swap! mail-state/*mail-page-state dissoc :inline-edit-message :inline-edit-title)))
        cancel! #(swap! mail-state/*mail-page-state dissoc :inline-edit-message :inline-edit-title)]
    [:input.inline-title-edit
     {:type "text"
      :auto-complete "off"
      :auto-focus true
      :value value
      :on-click #(.stopPropagation %)
      :on-change #(swap! mail-state/*mail-page-state assoc :inline-edit-title (.. % -target -value))
      :on-key-down (fn [e]
                     (case (.-key e)
                       "Enter" (do (.stopPropagation e) (commit!))
                       "Escape" (do (.stopPropagation e) (cancel!))
                       nil))
      :on-blur (fn [_] (commit!))}]))

(defn- mail-message-header [{:keys [id sender title created_at] :as message} expanded?]
  (let [inline-editing? (= id (:inline-edit-message @mail-state/*mail-page-state))]
    [:div.item-header
     {:on-click (fn [_]
                  (when-not inline-editing?
                    (state/set-expanded-message (when-not expanded? id))))}
     [:span.item-title
      [:span.mail-sender {:on-click (fn [e]
                                      (.stopPropagation e)
                                      (if (and (.-shiftKey e)
                                               (nil? (:sender-filter @mail-state/*mail-page-state)))
                                        (state/toggle-excluded-sender sender)
                                        (state/set-mail-sender-filter sender)))}
       sender]
      (if inline-editing?
        [mail-message-inline-title-edit message]
        [:span.mail-title
         {:on-click (fn [e]
                      (when (and expanded? (.-altKey e))
                        (.stopPropagation e)
                        (swap! mail-state/*mail-page-state assoc
                               :inline-edit-message id
                               :inline-edit-title title)))}
         title])
      (when (and expanded? (not inline-editing?))
        [:button.copy-icon {:on-click (fn [e]
                                        (.stopPropagation e)
                                        (.writeText js/navigator.clipboard title))}
         "⧉"])]
     [:span.item-date {:data-tooltip (some-> created_at (.substring 0 10) date/get-day-name)}
      (format-message-datetime created_at)]]))

(defn- archive-button-with-dropdown [{:keys [id title description done] :as _message}]
  (let [url (first-url title description)
        dropdown-open? (= id (:message-dropdown-open @mail-state/*mail-page-state))
        toggle-dropdown! #(state/set-message-dropdown-open (when-not dropdown-open? id))
        convert-to-resource! #(do (state/set-message-dropdown-open nil)
                                  (state/convert-message-to-resource id url))
        convert-to-task! #(do (state/set-message-dropdown-open nil)
                              (state/convert-message-to-task id))]
    (cond
      (not= done 1)
      [:div.combined-button-wrapper
       [:button.combined-main-btn.done
        {:on-click #(state/set-message-done id true)}
        (t :mail/archive)]
       [:button.combined-dropdown-btn.done
        {:on-click toggle-dropdown!}
        "▼"]
       (when dropdown-open?
         [:div.task-dropdown-menu
          (when url
            [:button.dropdown-item.convert-to-resource
             {:on-click convert-to-resource!}
             (t :mail/convert-to-resource)])
          [:button.dropdown-item.convert-to-task
           {:on-click convert-to-task!}
           (t :mail/convert-to-task)]])]

      url
      [:div.combined-button-wrapper
       [:button.combined-main-btn.done
        {:on-click toggle-dropdown!}
        (t :mail/convert-to)]
       [:button.combined-dropdown-btn.done
        {:on-click toggle-dropdown!}
        "▼"]
       (when dropdown-open?
         [:div.task-dropdown-menu
          [:button.dropdown-item.convert-to-resource
           {:on-click convert-to-resource!}
           (t :mail/convert-target-resource)]
          [:button.dropdown-item.convert-to-task
           {:on-click convert-to-task!}
           (t :mail/convert-target-task)]])]

      :else
      [:button.combined-main-btn.standalone.done
       {:on-click convert-to-task!}
       (t :mail/convert-to-task)])))

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

(defn- resource-only-message-actions [{:keys [id title description] :as message}]
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
  (if (#{"YouTube" "Podcasts"} (:sender message))
    [resource-only-message-actions message]
    (let [page-state @mail-state/*mail-page-state
          view (:view page-state)
          dropdown-open? (= (:id message) (:message-action-dropdown-open page-state))
          show-merge? (and (= view :inbox) (= (:sender message) "Note") next-message-id)]
      [:div.item-actions
       [archive-button-with-dropdown message]
       [message-scope-selector message]
       [message-importance-selector message]
       [message-urgency-selector message]
       (if show-merge?
         [:div.combined-button-wrapper
          [:button.delete-btn {:on-click #(state/set-confirm-delete-message message)}
           (t :task/delete)]
          [:button.combined-dropdown-btn.delete-btn
           {:on-click #(state/set-message-action-dropdown-open (when-not dropdown-open? (:id message)))}
           "▼"]
          (when dropdown-open?
            [:div.task-dropdown-menu
             [:button.dropdown-item
              {:on-click #(do
                            (state/set-message-action-dropdown-open nil)
                            (state/merge-message-with-below (:id message) next-message-id))}
              (t :mail/merge-with-below)]])]
         [:button.delete-btn {:on-click #(state/set-confirm-delete-message message)}
          (t :task/delete)])])))

(defn- mail-message-expanded-content [{:keys [title description] :as message} next-message-id]
  [:div.item-details
   (when-let [video-id (first-youtube-video-id title description)]
     [youtube-embed video-id])
   (if (seq description)
     [:div.description-wrapper
      [clampable-description
       {:text description
        :on-click #(state/set-editing-modal :message message)}]
      [:button.copy-icon {:on-click #(.writeText js/navigator.clipboard description)} "⧉"]]
     [:button.edit-icon.description-placeholder
      {:on-click (fn [e]
                   (.stopPropagation e)
                   (state/set-editing-modal :message message))}
      "✎"])
   [mail-message-actions message next-message-id]])

(defn- archive-checkbox [message archiving? expanded?]
  [:div.archive-checkbox-wrapper
   [:input.archive-checkbox
    {:type "checkbox"
     :checked @archiving?
     :on-click #(.stopPropagation %)
     :on-change (fn [_]
                  (reset! archiving? true)
                  (when expanded?
                    (state/set-expanded-message nil))
                  (js/setTimeout #(state/set-message-done (:id message) true) 1000))}]])

(defn- mail-message-item [_message _expanded-id _view _next-message-id]
  (let [archiving? (r/atom false)]
    (fn [message expanded-id view next-message-id]
      (let [{:keys [id]} message
            expanded? (= expanded-id id)
            show-checkbox? (= view :inbox)]
        [:li {:class (str (when expanded? "expanded")
                          (when @archiving? " archiving-out"))}
         [:div.mail-item-row
          (when show-checkbox?
            [archive-checkbox message archiving? expanded?])
          [:div.mail-item-content
           [mail-message-header message expanded?]]]
         (when expanded?
           [mail-message-expanded-content message next-message-id])]))))

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
  (let [sort-mode (mail-state/current-sort-mode @mail-state/*mail-page-state)]
    [:div.sort-toggle.toggle-group
     [:button {:class (when (= sort-mode :recent) "active")
               :on-click #(state/set-mail-sort-mode :recent)}
      (t :mail/sort-added)]
     [:button {:class (when (= sort-mode :reverse) "active")
               :on-click #(state/set-mail-sort-mode :reverse)}
      (t :mail/sort-reverse)]]))

(defn- mail-view-toggle []
  (let [view (:view @mail-state/*mail-page-state)]
    [:div.view-toggle.toggle-group
     [:button {:class (when (= view :saved) "active")
               :on-click #(state/set-mail-view (if (= view :saved) :inbox :saved))}
      (t :mail/view-saved)]]))

(defn- any-filter-active? []
  (let [{:keys [sender-filter excluded-senders importance-filter urgency-filter]} @mail-state/*mail-page-state]
    (or sender-filter (seq excluded-senders) importance-filter urgency-filter)))

(defn- mail-search-bar []
  (let [term (:search-term @mail-state/*mail-page-state)]
    [:div.mail-search-bar
     [:input {:type "text"
              :auto-complete "off"
              :value term
              :placeholder (t :mail/search-placeholder)
              :on-change #(state/set-mail-search-term (-> % .-target .-value))
              :on-key-down #(when (= (.-key %) "Escape")
                              (state/set-mail-search-term ""))}]
     (when (seq term)
       [:button.clear-search {:on-click #(state/set-mail-search-term "")} "x"])]))

(defn- mail-add-form []
  (let [input-val (r/atom "")]
    (fn []
      (let [disabled? (or (str/blank? @input-val) (any-filter-active?))]
        [:div.mail-add-form
         [:input {:id "mail-add-input"
                  :type "text"
                  :auto-complete "off"
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
        page-state @mail-state/*mail-page-state
        {:keys [expanded-message view]} page-state
        sort-mode (mail-state/current-sort-mode page-state)]
    [:div.mail-page
     [:div.tasks-header
      (when (= view :saved)
        [:<>
         [importance-filter-toggle]
         [urgency-filter-toggle]])
      [mail-sort-toggle]
      [mail-view-toggle]]
     (when (and (= view :inbox) (= sort-mode :recent))
       [mail-add-form])
     (when (= view :saved)
       [mail-search-bar])
     [mail-sender-filter-badge]
     (if (empty? messages)
       [:p.empty-message (t :mail/no-messages)]
       (let [indexed (map-indexed vector messages)]
         [:ul.items
          (for [[idx message] indexed]
            (let [next-message-id (some-> (get messages (inc idx)) :id)]
              ^{:key (:id message)}
              [mail-message-item message expanded-message view next-message-id]))]))]))
