(ns et.tr.ui.mail
  (:require [et.tr.ui.state :as state]
            [et.tr.ui.state.mail :as mail-state]
            [et.tr.ui.views.sources :as sources-view]
            [et.tr.i18n :refer [t]]
            [reagent.core :as r]
            [clojure.string :as str]
            [et.tr.ui.components.task-item :refer [clampable-description]]
            [et.tr.ui.components.item-card :as item-card]
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

(defn- mail-footer [message]
  (let [{:keys [id title description sender]} message
        url (first-url title description)
        scope-spec {:type :scope :value (:scope message)
                    :on-set #(state/set-message-scope id %)}
        importance-spec {:type :importance :value (:importance message)
                         :on-set #(state/set-message-importance id %)}
        urgency-spec {:type :urgency :value (:urgency message)
                      :on-set #(state/set-message-urgency id %)}]
    (if (#{"YouTube" "Podcasts"} sender)
      {:left (into [(when url
                      {:type :custom
                       :render [:button.combined-main-btn.done
                                {:on-click #(state/convert-message-to-resource id url)}
                                (t :mail/convert-to-resource)]})
                    scope-spec importance-spec urgency-spec]
                   [])
       :right [{:type :custom
                :render [:button.delete-btn {:on-click #(state/set-confirm-delete-message message)}
                         (t :task/delete)]}]}
      {:left [{:type :custom :render [archive-button-with-dropdown message]}
              scope-spec importance-spec urgency-spec]
       :right [{:type :custom
                :render [:button.delete-btn {:on-click #(state/set-confirm-delete-message message)}
                         (t :task/delete)]}]})))

(defn- mail-expanded-prefix [{:keys [title description type] :as message}]
  [:<>
   (when-let [video-id (first-youtube-video-id title description)]
     [youtube-embed video-id])
   (if (seq description)
     [:div.description-wrapper
      [clampable-description
       {:text description
        :content-type type
        :on-click #(state/set-editing-modal :message message)}]
      [:button.copy-icon {:on-click #(.writeText js/navigator.clipboard description)} "⧉"]]
     [:button.edit-icon.description-placeholder
      {:on-click (fn [e]
                   (.stopPropagation e)
                   (state/set-editing-modal :message message))}
      "✎"])])

(defn- mail-title-content [{:keys [item expanded? editing? title-el]}]
  [:<>
   [:span.mail-sender {:on-click (fn [e]
                                   (.stopPropagation e)
                                   (if (and (.-shiftKey e)
                                            (nil? (:sender-filter @mail-state/*mail-page-state)))
                                     (state/toggle-excluded-sender (:sender item))
                                     (state/set-mail-sender-filter (:sender item))))}
    (:sender item)]
   title-el
   (when (and expanded? (not editing?))
     [:button.copy-icon {:on-click (fn [e]
                                     (.stopPropagation e)
                                     (.writeText js/navigator.clipboard (:title item)))}
      "⧉"])])

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

(defn- mail-message-item [_message _expanded-id _view]
  (let [archiving? (r/atom false)]
    (fn [message expanded-id view]
      (let [{:keys [id created_at]} message
            expanded? (= expanded-id id)
            show-checkbox? (= view :inbox)]
        [item-card/item-card
         {:item message
          :expanded? expanded?
          :on-toggle #(state/set-expanded-message (when-not expanded? id))
          :container {:tag :li
                      :class (when @archiving? "archiving-out")}
          :header-wrapper (fn [hdr]
                            [:div.mail-item-row
                             (when show-checkbox?
                               [archive-checkbox message archiving? expanded?])
                             [:div.mail-item-content hdr]])
          :inline-edit (item-card/make-inline-edit
                         {:edit-id-path :inline-edit-message
                          :title-path :inline-edit-title
                          :update-fn state/update-message
                          :state-atom mail-state/*mail-page-state
                          :build-args (fn [item title-value done-cb]
                                        [(:id item) title-value (or (:description item) "") done-cb])})
          :title-text-class "mail-title"
          :title-content mail-title-content
          :header-extra [:span.item-date {:data-tooltip (some-> created_at (.substring 0 10) date/get-day-name)}
                         (format-message-datetime created_at)]
          :expanded-prefix [mail-expanded-prefix message]
          :footer (mail-footer message)}]))))

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

(defn- sources-toggle []
  (let [active? (state/sources-mode?)]
    [:div.series-mode-toggle.toggle-group
     [:button {:class (when active? "active")
               :on-click #(state/toggle-sources-mode)}
      (t :sources/sources)]]))

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
        sort-mode (mail-state/current-sort-mode page-state)
        sources? (state/sources-mode?)]
    [:div.mail-page
     [:div.tasks-header
      [sources-toggle]
      (when (and (not sources?) (= view :saved))
        [:<>
         [importance-filter-toggle]
         [urgency-filter-toggle]])
      (when-not sources?
        [mail-sort-toggle])
      (when-not sources?
        [mail-view-toggle])]
     (cond
       sources?
       [sources-view/sources-page]

       :else
       [:<>
        (when (and (= view :inbox) (= sort-mode :recent))
          [mail-add-form])
        (when (= view :saved)
          [mail-search-bar])
        [mail-sender-filter-badge]
        (if (empty? messages)
          [:p.empty-message (t :mail/no-messages)]
          [:ul.items
           (for [message messages]
             ^{:key (:id message)}
             [mail-message-item message expanded-message view])])])]))
