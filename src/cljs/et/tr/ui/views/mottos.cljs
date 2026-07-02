(ns et.tr.ui.views.mottos
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [et.tr.ui.state :as state]
            [et.tr.ui.state.mottos :as mottos-state]
            [et.tr.i18n :refer [t]]))

(defn- scope-selector [motto]
  (let [scope (or (:scope motto) "both")]
    [:div.motto-scope-selector.toggle-group.compact
     (for [s ["private" "both" "work"]]
       ^{:key s}
       [:button.toggle-option
        {:class (when (= scope s) "active")
         :on-click (fn [e]
                     (.stopPropagation e)
                     (state/set-motto-scope (:id motto) s))}
        (t (keyword "toggle" s))])]))

(defn- time-window-selector [motto]
  (let [tw (or (:time_window motto) "both")]
    [:div.motto-time-window-selector.toggle-group.compact
     (for [[value label-key] [["both" :mottos/time-normal]
                              ["daytime" :mottos/time-before-8]
                              ["nighttime" :mottos/time-after-8]]]
       ^{:key value}
       [:button.toggle-option
        {:class (when (= tw value) "active")
         :on-click (fn [e]
                     (.stopPropagation e)
                     (state/set-motto-time-window (:id motto) value))}
        (t label-key)])]))

(defn- motto-edit-row [motto on-done]
  (let [title (r/atom (:title motto))
        description (r/atom (or (:description motto) ""))
        save (fn []
               (let [t* (str/trim @title)]
                 (when (seq t*)
                   (state/update-motto (:id motto) t* @description (:modified_at motto) on-done))))]
    (fn [motto _]
      [:div.motto-edit
       [:input.motto-edit-title
        {:type "text"
         :auto-complete "off"
         :placeholder (t :mottos/title-placeholder)
         :value @title
         :on-change #(reset! title (-> % .-target .-value))
         :on-key-down (fn [e]
                        (when (= (.-key e) "Escape") (on-done)))}]
       [:textarea.motto-edit-description
        {:placeholder (t :mottos/description-placeholder)
         :value @description
         :rows 3
         :on-change #(reset! description (-> % .-target .-value))}]
       [:div.motto-edit-buttons
        [:button.motto-save-btn
         {:on-click save
          :disabled (str/blank? @title)}
         (t :mottos/save)]
        [:button {:on-click on-done} (t :mottos/cancel)]]])))

(defn- motto-row [motto]
  (let [editing? (= (:editing-motto @mottos-state/*mottos-page-state) (:id motto))
        confirm-delete? (= (:id (:confirm-delete-motto @mottos-state/*mottos-page-state)) (:id motto))]
    [:li.motto-row
     {:class (when editing? "editing")}
     (if editing?
       [motto-edit-row motto #(state/clear-editing-motto)]
       [:div.motto-view
        [:div.motto-main
         [:span.motto-title (:title motto)]
         (when (seq (:description motto))
           [:span.motto-description (:description motto)])]
        [:div.motto-controls
         [scope-selector motto]
         [time-window-selector motto]
         [:button.motto-edit-btn
          {:on-click #(state/set-editing-motto (:id motto))}
          (t :mottos/edit)]
         (if confirm-delete?
           [:span.motto-confirm-delete
            (t :mottos/delete-confirm)
            [:button.motto-delete-confirm-btn
             {:on-click #(state/delete-motto (:id motto))}
             (t :mottos/delete-yes)]
            [:button {:on-click #(state/clear-confirm-delete-motto)}
             (t :mottos/cancel)]]
           [:button.motto-delete-btn
            {:on-click #(state/set-confirm-delete-motto motto)}
            (t :mottos/delete)])]])]))

(defn- screensaver-controls []
  (let [current-user (:current-user @state/*app-state)
        enabled? (= 1 (:screensaver_enabled current-user))
        timeout-seconds (or (:screensaver_timeout_seconds current-user) 300)]
    [:div.motto-screensaver-controls
     [:label.motto-screensaver-toggle
      [:input {:type "checkbox"
               :checked enabled?
               :on-change #(state/update-screensaver-enabled (not enabled?))}]
      [:span (t :mottos/screensaver-opt-in)]]
     [:label.motto-screensaver-timeout
      {:title (t :mottos/screensaver-timeout-help)}
      [:span (t :mottos/screensaver-timeout-label)]
      [:input {:type "number"
               :min 5
               :step 5
               :value timeout-seconds
               :disabled (not enabled?)
               :on-change (fn [e]
                            (let [n (js/parseInt (.. e -target -value) 10)]
                              (when (and (integer? n) (pos? n))
                                (state/update-screensaver-timeout n))))}]
      [:span.motto-screensaver-timeout-unit (t :mottos/screensaver-seconds)]]]))

(defn- add-motto-form []
  (let [title (r/atom "")
        description (r/atom "")
        reset-form (fn []
                     (reset! title "")
                     (reset! description ""))
        do-add (fn []
                 (let [t* (str/trim @title)]
                   (when (seq t*)
                     (state/add-motto t* @description reset-form))))]
    (fn []
      [:div.motto-add-form
       [:input#mottos-filter-search.motto-search
        {:type "text"
         :auto-complete "off"
         :placeholder (t :mottos/search-placeholder)
         :value (:filter-search @mottos-state/*mottos-page-state)
         :on-change #(state/set-motto-filter-search (-> % .-target .-value))
         :on-key-down (fn [e]
                        (cond
                          (= (.-key e) "Escape")
                          (state/set-motto-filter-search "")))}]
       [:input.motto-add-title
        {:type "text"
         :auto-complete "off"
         :placeholder (t :mottos/title-placeholder)
         :value @title
         :on-change #(reset! title (-> % .-target .-value))
         :on-key-down (fn [e]
                        (when (= (.-key e) "Enter")
                          (.preventDefault e)
                          (do-add)))}]
       [:textarea.motto-add-description
        {:placeholder (t :mottos/description-placeholder)
         :rows 2
         :value @description
         :on-change #(reset! description (-> % .-target .-value))}]
       [:button.motto-add-btn
        {:on-click do-add
         :disabled (str/blank? @title)}
        (t :mottos/add-button)]])))

(defn mottos-tab []
  (r/create-class
    {:component-did-mount (fn [] (state/fetch-mottos))
     :reagent-render
     (fn []
       (let [mottos (:mottos @state/*app-state)
             search (:filter-search @mottos-state/*mottos-page-state)]
         [:div.settings-page
          [:div.mottos-page
           [:div.manage-section.settings-section
            [:h3 (t :settings/mottos)]
            [:p.muted (t :settings/mottos-help)]
            [screensaver-controls]]
           [:div.manage-section.settings-section
            [add-motto-form]
            (if (empty? mottos)
              [:p.muted
               (if (seq search)
                 (t :mottos/no-results)
                 (t :mottos/empty))]
              [:ul.items.motto-list
               (for [m mottos]
                 ^{:key (:id m)} [motto-row m])])]]]))}))
