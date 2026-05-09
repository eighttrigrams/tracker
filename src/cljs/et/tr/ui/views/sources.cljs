(ns et.tr.ui.views.sources
  (:require [reagent.core :as r]
            [et.tr.ui.state :as state]
            [et.tr.ui.state.sources :as sources-state]
            [et.tr.i18n :refer [t]]))

(defn- youtube-settings-row []
  (let [editing (r/atom nil)]
    (fn []
      (let [{:keys [settings]} @sources-state/*sources-page-state
            enabled? (boolean (:enabled settings))
            polling-minutes (or (:polling_minutes settings) 60)
            display (or @editing (str polling-minutes))]
        [:div.sources-settings-row
         [:label.sources-toggle-label
          [:input {:type "checkbox"
                   :checked enabled?
                   :on-change #(state/set-youtube-source-enabled
                                (.. % -target -checked))}]
          (t :sources/youtube-enabled)]
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
                           (state/set-youtube-source-polling-minutes n)))
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
                    (let [trimmed (clojure.string/trim @name-val)
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
     [:button {:disabled (clojure.string/blank? (:add-channel-id page-state))
               :on-click #(state/add-youtube-channel)}
      (t :sources/add-channel)]]))

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
      [youtube-settings-row]
      [add-channel-form]
      (cond
        (not loaded?) [:p (t :sources/loading)]
        (empty? channels) [:p.empty-message (t :sources/no-channels)]
        :else [:ul.sources-channels-list
               (for [ch channels]
                 ^{:key (:id ch)}
                 [channel-row ch])])]]))
