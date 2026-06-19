(ns et.tr.ui.views.sources
  (:require [reagent.core :as r]
            [clojure.string :as str]
            [et.tr.ui.state :as state]
            [et.tr.ui.state.sources :as sources-state]
            [et.tr.ui.components.controls :as controls]
            [et.tr.i18n :refer [t]]))

(defn- settings-row
  "Generic enabled/polling-minutes row used by every source section."
  [{:keys [settings-key enabled-label set-enabled set-polling-minutes]}]
  (let [editing (r/atom nil)]
    (fn [_]
      (let [settings (get @sources-state/*sources-page-state settings-key)
            enabled? (boolean (:enabled settings))
            polling-minutes (or (:polling_minutes settings) 60)
            display (or @editing (str polling-minutes))]
        [:div.sources-settings-row
         [:label.sources-toggle-label
          [:input {:type "checkbox"
                   :checked enabled?
                   :on-change #(set-enabled (.. % -target -checked))}]
          enabled-label]
         [:div.sources-polling-cycle
          [:span.sources-polling-label (t :sources/polling-cycle)]
          [:input.sources-polling-input
           {:type "number" :min 1 :step 1
            :value display
            :on-focus #(reset! editing (str polling-minutes))
            :on-change #(reset! editing (.. % -target -value))
            :on-blur (fn [_]
                       (let [n (try (js/parseInt @editing 10) (catch :default _ nil))]
                         (when (and (number? n) (pos? n)
                                    (not= n polling-minutes))
                           (set-polling-minutes n)))
                       (reset! editing nil))
            :on-key-down (fn [e]
                           (when (= "Enter" (.-key e))
                             (.blur (.-target e))))}]
          [:span.sources-polling-suffix (t :sources/minutes)]]]))))

(defn- channel-row [channel]
  (let [name-val (r/atom (or (:name channel) ""))
        min-val (r/atom (if (some? (:min_duration_minutes channel))
                          (str (:min_duration_minutes channel))
                          ""))]
    (fn [channel]
      [:li.sources-channel-row
       [:label.sources-channel-enabled
        [:input {:type "checkbox"
                 :checked (boolean (:enabled channel))
                 :on-change #(state/set-youtube-channel-enabled
                              (:id channel) (.. % -target -checked))}]]
       [:span.sources-channel-id (:channel_id channel)]
       [:input.sources-channel-name-input
        {:type "text"
         :placeholder (t :sources/name-placeholder)
         :value @name-val
         :on-change #(reset! name-val (.. % -target -value))
         :on-blur (fn [_]
                    (let [trimmed (str/trim @name-val)
                          before (or (:name channel) "")]
                      (when (not= trimmed before)
                        (state/set-youtube-channel-name (:id channel)
                                                       (when (seq trimmed) trimmed)))))}]
       [:input.sources-channel-min-input
        {:type "number" :min 1 :step 1
         :placeholder (t :sources/min-placeholder)
         :value @min-val
         :on-change #(reset! min-val (.. % -target -value))
         :on-blur (fn [_]
                    (let [raw @min-val
                          n (when (seq raw)
                              (try (js/parseInt raw 10) (catch :default _ nil)))
                          before (:min_duration_minutes channel)]
                      (when (not= n before)
                        (state/set-youtube-channel-min-minutes (:id channel) n))))}]
       [controls/plain-scope-toggle "sources-channel-scope toggle-group compact"
        (or (:scope channel) "private")
        #(state/set-youtube-channel-scope (:id channel) %)]
       [controls/plain-importance-toggle "sources-channel-importance toggle-group compact"
        (or (:importance channel) "normal")
        #(state/set-youtube-channel-importance (:id channel) %)]
       [:button.sources-channel-delete
        {:on-click #(state/delete-youtube-channel (:id channel))
         :title (t :sources/delete-channel)}
        "x"]])))

(defn- add-channel-form []
  (let [page-state @sources-state/*sources-page-state]
    [:div.sources-add-form
     [:input.sources-add-channel-id
      {:type "text"
       :placeholder (t :sources/channel-id-placeholder)
       :value (:add-channel-id page-state)
       :on-change #(state/set-sources-form-field :add-channel-id (.. % -target -value))}]
     [:input.sources-add-channel-name
      {:type "text"
       :placeholder (t :sources/name-placeholder)
       :value (:add-channel-name page-state)
       :on-change #(state/set-sources-form-field :add-channel-name (.. % -target -value))}]
     [:input.sources-add-channel-min
      {:type "number" :min 1 :step 1
       :placeholder (t :sources/min-placeholder)
       :value (:add-channel-min page-state)
       :on-change #(state/set-sources-form-field :add-channel-min (.. % -target -value))}]
     [:button {:disabled (str/blank? (:add-channel-id page-state))
               :on-click #(state/add-youtube-channel)}
      (t :sources/add-channel)]]))

(defn- feed-row
  "Generic feed list row, parametrized by source-specific actions and key."
  [{:keys [set-enabled set-name delete]} feed]
  (let [name-val (r/atom (or (:name feed) ""))]
    (fn [_ feed]
      [:li.sources-channel-row
       [:label.sources-channel-enabled
        [:input {:type "checkbox"
                 :checked (boolean (:enabled feed))
                 :on-change #(set-enabled (:id feed) (.. % -target -checked))}]]
       [:span.sources-channel-id (:feed_url feed)]
       [:input.sources-channel-name-input
        {:type "text"
         :placeholder (t :sources/name-placeholder)
         :value @name-val
         :on-change #(reset! name-val (.. % -target -value))
         :on-blur (fn [_]
                    (let [trimmed (str/trim @name-val)
                          before (or (:name feed) "")]
                      (when (not= trimmed before)
                        (set-name (:id feed) (when (seq trimmed) trimmed)))))}]
       [:button.sources-channel-delete
        {:on-click #(delete (:id feed))
         :title (t :sources/delete-feed)}
        "x"]])))

(defn- add-feed-form
  [{:keys [url-key name-key url-placeholder add-fn]}]
  (let [page-state @sources-state/*sources-page-state]
    [:div.sources-add-form
     [:input.sources-add-channel-id
      {:type "text"
       :placeholder url-placeholder
       :value (get page-state url-key)
       :on-change #(state/set-sources-form-field url-key (.. % -target -value))}]
     [:input.sources-add-channel-name
      {:type "text"
       :placeholder (t :sources/name-placeholder)
       :value (get page-state name-key)
       :on-change #(state/set-sources-form-field name-key (.. % -target -value))}]
     [:button {:disabled (str/blank? (get page-state url-key))
               :on-click add-fn}
      (t :sources/add-feed)]]))

(defn- feed-section
  [{:keys [heading feeds-key empty-msg url-placeholder
           settings-key enabled-label
           set-source-enabled set-source-polling-minutes
           add-fn set-feed-enabled set-feed-name delete-feed
           url-key name-key]}]
  (let [{:keys [loaded?]} @sources-state/*sources-page-state
        feeds (get @sources-state/*sources-page-state feeds-key)]
    [:section.sources-section
     [:h2.sources-heading heading]
     [settings-row {:settings-key settings-key
                    :enabled-label enabled-label
                    :set-enabled set-source-enabled
                    :set-polling-minutes set-source-polling-minutes}]
     [add-feed-form {:url-key url-key
                     :name-key name-key
                     :url-placeholder url-placeholder
                     :add-fn add-fn}]
     (cond
       (not loaded?) [:p (t :sources/loading)]
       (empty? feeds) [:p.empty-message empty-msg]
       :else [:ul.sources-channels-list
              (for [f feeds]
                ^{:key (:id f)}
                [feed-row {:set-enabled set-feed-enabled
                           :set-name set-feed-name
                           :delete delete-feed}
                 f])])]))

(defn sources-page []
  (let [{:keys [channels loaded? error]} @sources-state/*sources-page-state]
    [:div.sources-page
     (when error
       [:div.error error
        [:button.error-dismiss
         {:on-click #(swap! sources-state/*sources-page-state assoc :error nil)}
         "x"]])
     [:section.sources-section
      [:h2.sources-heading (t :sources/youtube-heading)]
      [settings-row {:settings-key :settings
                     :enabled-label (t :sources/youtube-enabled)
                     :set-enabled state/set-youtube-source-enabled
                     :set-polling-minutes state/set-youtube-source-polling-minutes}]
      [add-channel-form]
      (cond
        (not loaded?) [:p (t :sources/loading)]
        (empty? channels) [:p.empty-message (t :sources/no-channels)]
        :else [:ul.sources-channels-list
               (for [ch channels]
                 ^{:key (:id ch)}
                 [channel-row ch])])]
     [feed-section
      {:heading (t :sources/podcast-heading)
       :feeds-key :podcast-feeds
       :empty-msg (t :sources/no-feeds)
       :url-placeholder (t :sources/podcast-url-placeholder)
       :settings-key :podcast-settings
       :enabled-label (t :sources/podcast-enabled)
       :set-source-enabled state/set-podcast-source-enabled
       :set-source-polling-minutes state/set-podcast-source-polling-minutes
       :add-fn state/add-podcast-feed
       :set-feed-enabled state/set-podcast-feed-enabled
       :set-feed-name state/set-podcast-feed-name
       :delete-feed state/delete-podcast-feed
       :url-key :add-podcast-url
       :name-key :add-podcast-name}]
     [feed-section
      {:heading (t :sources/atom-heading)
       :feeds-key :atom-feeds
       :empty-msg (t :sources/no-feeds)
       :url-placeholder (t :sources/atom-url-placeholder)
       :settings-key :atom-settings
       :enabled-label (t :sources/atom-enabled)
       :set-source-enabled state/set-atom-source-enabled
       :set-source-polling-minutes state/set-atom-source-polling-minutes
       :add-fn state/add-atom-feed
       :set-feed-enabled state/set-atom-feed-enabled
       :set-feed-name state/set-atom-feed-name
       :delete-feed state/delete-atom-feed
       :url-key :add-atom-url
       :name-key :add-atom-name}]]))
