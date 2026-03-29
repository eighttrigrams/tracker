(ns et.tr.ui.modals
  (:require [reagent.core :as r]
            [clojure.string]
            [et.tr.ui.state :as state]
            [et.tr.ui.url :as url]
            [et.tr.ui.state.mail :as mail-state]
            [et.tr.ui.state.resources :as resources-state]
            [et.tr.ui.state.meets :as meets-state]
            [et.tr.ui.state.meeting-series :as meeting-series-state]
            [et.tr.ui.state.recurring-tasks :as recurring-tasks-state]
            [et.tr.ui.components.cm-textarea :refer [cm-textarea]]
            [et.tr.i18n :refer [t tf]]
            ["marked" :refer [marked]]))

(defn- generic-confirm-modal
  [{:keys [header body-paragraphs on-cancel on-confirm]}]
  [:div.modal-overlay
   [:div.modal {:on-click #(.stopPropagation %)}
    [:div.modal-header header]
    [:div.modal-body
     (for [[idx p] (map-indexed vector body-paragraphs)]
       ^{:key idx}
       (if (:class p)
         [:p {:class (:class p)} (:text p)]
         [:p (:text p)]))]
    [:div.modal-footer
     [:button.cancel {:on-click on-cancel} (t :modal/cancel)]
     [:button.confirm-delete {:on-click on-confirm} (t :modal/delete)]]]])

(defn- make-confirm-delete-modal [{:keys [state-atom state-key header-i18n confirm-i18n title-key clear-fn delete-fn]}]
  (fn []
    (when-let [entity (state-key @state-atom)]
      [generic-confirm-modal
       {:header (t header-i18n)
        :body-paragraphs [{:text (t confirm-i18n)}
                          {:text (title-key entity) :class "task-title"}]
        :on-cancel clear-fn
        :on-confirm #(delete-fn (:id entity))}])))

(def confirm-delete-modal
  (make-confirm-delete-modal
   {:state-atom state/*app-state
    :state-key :confirm-delete-task
    :header-i18n :modal/delete-task
    :confirm-i18n :modal/delete-task-confirm
    :title-key :title
    :clear-fn state/clear-confirm-delete
    :delete-fn state/delete-task}))

(defn confirm-delete-user-modal []
  (let [confirmation-input (r/atom "")]
    (fn []
      (when-let [user (:confirm-delete-user @state/*app-state)]
        (let [username (:username user)
              matches? (= @confirmation-input username)]
          [:div.modal-overlay
           [:div.modal {:on-click #(.stopPropagation %)}
            [:div.modal-header (t :modal/delete-user)]
            [:div.modal-body
             [:p (t :modal/delete-user-confirm)]
             [:p.task-title username]
             [:p.warning (t :modal/delete-user-warning)]
             [:p {:style {:margin-top "16px"}} (tf :modal/delete-user-type-confirm username)]
             [:input {:type "text"
                      :value @confirmation-input
                      :on-change #(reset! confirmation-input (-> % .-target .-value))
                      :placeholder (t :modal/enter-username)
                      :style {:width "100%" :margin-top "8px"}}]]
            [:div.modal-footer
             [:button.cancel {:on-click #(do (reset! confirmation-input "") (state/clear-confirm-delete-user))} (t :modal/cancel)]
             [:button.confirm-delete {:disabled (not matches?)
                                      :on-click #(do (reset! confirmation-input "") (state/delete-user (:id user)))} (t :modal/delete)]]]])))))

(defn confirm-delete-category-modal []
  (when-let [{:keys [type category]} (:confirm-delete-category @state/*app-state)]
    (let [type-label (case type
                       "person" (t :category/person)
                       "place" (t :category/place)
                       "project" (t :category/project)
                       "goal" (t :category/goal)
                       type)
          delete-fn (case type
                      "person" state/delete-person
                      "place" state/delete-place
                      "project" state/delete-project
                      "goal" state/delete-goal)]
      [generic-confirm-modal
       {:header (tf :modal/delete-category type-label)
        :body-paragraphs [{:text (tf :modal/delete-category-confirm type-label)}
                          {:text (:name category) :class "task-title"}
                          {:text (tf :modal/delete-category-warning type-label) :class "warning"}]
        :on-cancel state/clear-confirm-delete-category
        :on-confirm #(delete-fn (:id category))}])))

(def confirm-delete-message-modal
  (make-confirm-delete-modal
   {:state-atom mail-state/*mail-page-state
    :state-key :confirm-delete-message
    :header-i18n :modal/delete-message
    :confirm-i18n :modal/delete-message-confirm
    :title-key :title
    :clear-fn state/clear-confirm-delete-message
    :delete-fn state/delete-message}))

(def confirm-delete-resource-modal
  (make-confirm-delete-modal
   {:state-atom resources-state/*resources-page-state
    :state-key :confirm-delete-resource
    :header-i18n :modal/delete-resource
    :confirm-i18n :modal/delete-resource-confirm
    :title-key :title
    :clear-fn state/clear-confirm-delete-resource
    :delete-fn state/delete-resource}))

(def confirm-delete-meet-modal
  (make-confirm-delete-modal
   {:state-atom meets-state/*meets-page-state
    :state-key :confirm-delete-meet
    :header-i18n :modal/delete-meet
    :confirm-i18n :modal/delete-meet-confirm
    :title-key :title
    :clear-fn state/clear-confirm-delete-meet
    :delete-fn state/delete-meet}))

(def confirm-delete-meeting-series-modal
  (make-confirm-delete-modal
   {:state-atom meeting-series-state/*meeting-series-page-state
    :state-key :confirm-delete-series
    :header-i18n :modal/delete-meeting-series
    :confirm-i18n :modal/delete-meeting-series-confirm
    :title-key :title
    :clear-fn state/clear-confirm-delete-series
    :delete-fn state/delete-meeting-series}))

(def confirm-delete-recurring-task-modal
  (make-confirm-delete-modal
   {:state-atom recurring-tasks-state/*recurring-tasks-page-state
    :state-key :confirm-delete-rtask
    :header-i18n :modal/delete-recurring-task
    :confirm-i18n :modal/delete-recurring-task-confirm
    :title-key :title
    :clear-fn state/clear-confirm-delete-rtask
    :delete-fn state/delete-recurring-task}))

(defn category-tag-item [category-type id name selected? toggle-fn]
  [:span.tag.selectable
   {:class (str category-type (when selected? " selected"))
    :on-click #(toggle-fn category-type id)}
   name
   (when selected? [:span.check " ✓"])])

(defn- category-group [state-key category-type selected-set i18n-label-key]
  (when (seq selected-set)
    (let [items (filter #(contains? selected-set (:id %)) (state-key @state/*app-state))]
      [:div.category-group
       [:label (str (t i18n-label-key) ":")]
       [:div.category-tags
        (doall
         (for [item items]
           ^{:key (:id item)}
           [category-tag-item category-type (:id item) (:name item)
            true
            state/update-pending-category]))]])))

(defn pending-item-modal []
  (when-let [{:keys [type title categories]} (:pending-new-item @state/*app-state)]
    (let [{:keys [people places projects goals]} categories
          selected-people (or people #{})
          selected-places (or places #{})
          selected-projects (or projects #{})
          selected-goals (or goals #{})
          header-key (case type :task :modal/add-task-categories :resource :modal/add-resource-categories :recurring-task :modal/add-recurring-task-categories (:meet :meeting-series) :modal/add-meet-categories)
          confirm-key (case type :task :modal/add-task :resource :modal/add-resource :recurring-task :modal/add-recurring-task (:meet :meeting-series) :modal/add-meet)]
      [:div.modal-overlay {:on-click #(state/clear-pending-new-item)}
       [:div.modal.pending-task-modal {:on-click #(.stopPropagation %)}
        [:div.modal-header (t header-key)]
        [:div.modal-body
         [:p.task-title title]
         [:p.modal-instruction (t :modal/select-categories)]
         [category-group :people state/CATEGORY-TYPE-PERSON selected-people :category/people]
         [category-group :places state/CATEGORY-TYPE-PLACE selected-places :category/places]
         [category-group :projects state/CATEGORY-TYPE-PROJECT selected-projects :category/projects]
         (when (= type :task)
           [category-group :goals state/CATEGORY-TYPE-GOAL selected-goals :category/goals])]
        [:div.modal-footer
         [:button.cancel {:on-click #(state/clear-pending-new-item)} (t :modal/cancel)]
         [:button.confirm {:on-click #(state/confirm-pending-new-item)} (t confirm-key)]]]])))

(defn- markdown-preview [text]
  [:div.preview-description
   {:dangerouslySetInnerHTML {:__html (marked (or text ""))}}])

(defn- edit-modal-fields [{:keys [type entity]}]
  (let [field-atoms (case type
                      (:task :meet) {:title (r/atom (:title entity))
                                     :description (r/atom (or (:description entity) ""))
                                     :tags (r/atom (or (:tags entity) ""))}
                      (:meeting-series :recurring-task) {:title (r/atom (:title entity))
                                       :description (r/atom (or (:description entity) ""))
                                       :tags (r/atom (or (:tags entity) ""))
                                       :schedule-days (r/atom (or (:schedule_days entity) ""))
                                       :schedule-time (r/atom (or (:schedule_time entity) ""))
                                       :schedule-mode (r/atom (or (:schedule_mode entity) "weekly"))
                                       :biweekly-offset (r/atom (= 1 (:biweekly_offset entity)))}
                      :resource {:title (r/atom (:title entity))
                                 :link (r/atom (:link entity))
                                 :description (r/atom (or (:description entity) ""))
                                 :tags (r/atom (or (:tags entity) ""))}
                      {:title (r/atom (:name entity))
                       :description (r/atom (or (:description entity) ""))
                       :tags (r/atom (or (:tags entity) ""))
                       :badge-title (r/atom (or (:badge_title entity) ""))})]
    (assoc field-atoms :type type :entity entity)))

(defn- edit-modal-save [{:keys [type entity title description tags link badge-title schedule-days schedule-time schedule-mode biweekly-offset]}]
  (let [id (:id entity)]
    (case type
      :task (state/update-task id @title @description @tags state/clear-editing-modal)
      :meet (state/update-meet id @title @description @tags state/clear-editing-modal)
      :meeting-series (do (state/update-meeting-series id @title @description @tags state/clear-editing-modal)
                          (state/set-meeting-series-schedule id @schedule-days @schedule-time @schedule-mode @biweekly-offset nil))
      :recurring-task (do (state/update-recurring-task id @title @description @tags state/clear-editing-modal)
                          (state/set-recurring-task-schedule id @schedule-days @schedule-time @schedule-mode @biweekly-offset nil))
      :resource (state/update-resource id @title @link @description @tags state/clear-editing-modal)
      (let [category-type (subs (name type) 9)
            update-fn (case category-type
                        "person" state/update-person
                        "place" state/update-place
                        "project" state/update-project
                        "goal" state/update-goal)]
        (update-fn id @title @description @tags @badge-title state/clear-editing-modal)))))

(def ^:private day-keys
  [{:num "1" :label-key :date/mon}
   {:num "2" :label-key :date/tue}
   {:num "3" :label-key :date/wed}
   {:num "4" :label-key :date/thu}
   {:num "5" :label-key :date/fri}
   {:num "6" :label-key :date/sat}
   {:num "7" :label-key :date/sun}])

(defn- parse-schedule-days [s]
  (if (or (nil? s) (= s ""))
    #{}
    (set (clojure.string/split s #","))))

(defn- serialize-schedule-days [day-set]
  (clojure.string/join "," (sort day-set)))

(defn- per-day-time? [s]
  (and (some? s) (clojure.string/includes? s "=")))

(defn- parse-per-day-times [s]
  (if (or (nil? s) (= s "") (not (per-day-time? s)))
    {}
    (into {} (for [pair (clojure.string/split s #",")
                   :let [[d t] (clojure.string/split pair #"=" 2)]
                   :when (and d t)]
               [d t]))))

(defn- serialize-per-day-times [day-time-map]
  (clojure.string/join "," (for [[d t] (sort-by key day-time-map)] (str d "=" t))))

(defn- shared-time [s]
  (if (or (nil? s) (per-day-time? s)) "" s))

(defn- parse-time [time-str]
  (when (seq time-str)
    (let [[h m] (map js/parseInt (.split time-str ":"))]
      {:hour h :minute m})))

(defn- format-time [hour minute]
  (str (.padStart (str hour) 2 "0") ":" (.padStart (str minute) 2 "0")))

(defn- schedule-time-picker [_time-value _on-change]
  (let [open? (r/atom false)]
    (fn [time-value on-change]
      (let [parsed (parse-time time-value)
            current-hour (:hour parsed)
            current-minute (:minute parsed)]
        [:span.time-picker-wrapper
         {:on-click #(.stopPropagation %)}
         [:button.clock-icon
          {:on-click (fn [e]
                       (.stopPropagation e)
                       (when (and (not @open?) (not (seq time-value)))
                         (on-change "09:00"))
                       (swap! open? not))}
          (if (seq time-value) time-value "🕐")]
         (when @open?
           [:div.time-picker-dropdown
            {:on-click #(.stopPropagation %)}
            [:div.time-picker-columns
             [:div.time-picker-column
              [:div.time-picker-column-label "H"]
              [:div.time-picker-values
               (doall
                (for [h (range 24)]
                  ^{:key h}
                  [:button.time-picker-value
                   {:class (when (= h current-hour) "selected")
                    :on-click (fn [e]
                                (.stopPropagation e)
                                (on-change (format-time h (or current-minute 0))))}
                   (.padStart (str h) 2 "0")]))]]
             [:div.time-picker-column
              [:div.time-picker-column-label "M"]
              [:div.time-picker-values
               (doall
                (for [m (range 0 60 5)]
                  ^{:key m}
                  [:button.time-picker-value
                   {:class (when (= m current-minute) "selected")
                    :on-click (fn [e]
                                (.stopPropagation e)
                                (on-change (format-time (or current-hour 0) m)))}
                   (.padStart (str m) 2 "0")]))]]]
            [:div.time-picker-actions
             [:button.time-picker-close
              {:on-click (fn [e]
                           (.stopPropagation e)
                           (reset! open? false))}
              "Done"]]])]))))

(def ^:private month-days
  (mapv (fn [n] {:num (str n) :label (str n)}) (range 1 29)))

(defn- format-date [js-d]
  (let [y (.getFullYear js-d)
        m (.padStart (str (+ 1 (.getMonth js-d))) 2 "0")
        d (.padStart (str (.getDate js-d)) 2 "0")]
    (str y "-" m "-" d)))

(defn- today-date-str [] (format-date (js/Date.)))

(defn- scheduling-tab-content []
  (let [per-day? (r/atom false)
        day-times (r/atom {})
        shared-time-val (r/atom "")
        initialized? (r/atom false)]
    (fn [_entity schedule-days schedule-time schedule-mode biweekly-offset]
      (when-not @initialized?
        (reset! initialized? true)
        (let [st @schedule-time]
          (if (per-day-time? st)
            (do (reset! per-day? true)
                (reset! day-times (parse-per-day-times st))
                (reset! shared-time-val ""))
            (do (reset! per-day? false)
                (reset! day-times {})
                (reset! shared-time-val (shared-time st))))))
      (let [mode @schedule-mode
            active-days (parse-schedule-days @schedule-days)
            sync-schedule-time! (fn []
                                  (if (and @per-day? (= mode "weekly"))
                                    (reset! schedule-time (serialize-per-day-times @day-times))
                                    (reset! schedule-time @shared-time-val)))
            set-mode! (fn [new-mode]
                        (reset! schedule-mode new-mode)
                        (reset! schedule-days "")
                        (when (not= new-mode "weekly")
                          (reset! per-day? false))
                        (when (= new-mode "biweekly")
                          (reset! biweekly-offset false))
                        (sync-schedule-time!))]
        [:div.scheduling-form
         [:div.schedule-mode-selector.toggle-group
          [:button.toggle-option {:class (when (= mode "weekly") "active")
                                  :on-click #(set-mode! "weekly")}
           (t :scheduling/weekly)]
          [:button.toggle-option {:class (when (= mode "biweekly") "active")
                                  :on-click #(set-mode! "biweekly")}
           (t :scheduling/biweekly)]
          [:button.toggle-option {:class (when (= mode "monthly") "active")
                                  :on-click #(set-mode! "monthly")}
           (t :scheduling/monthly)]]

         (case mode
           "monthly"
           [:div
            [:div.schedule-month-days
             (doall
              (for [{:keys [num label]} month-days]
                ^{:key num}
                [:button.toggle-option.month-day
                 {:class (when (contains? active-days num) "active")
                  :on-click (fn [_]
                              (reset! schedule-days num))}
                 label]))]
            [:div.schedule-time-row
             [:label (t :scheduling/time)]
             [schedule-time-picker
              @shared-time-val
              (fn [v]
                (reset! shared-time-val v)
                (sync-schedule-time!))]]]

           "biweekly"
           (let [selected-day (first active-days)
                 offset-val @biweekly-offset
                 offset-int (if offset-val 1 0)
                 today-dow (let [d (.getDay (js/Date.))] (if (= d 0) 7 d))
                 rotated-days (let [idx (dec today-dow)]
                                (into (subvec day-keys idx) (subvec day-keys 0 idx)))
                 next-date (when selected-day
                             (state/next-biweekly-date-from selected-day offset-int (today-date-str)))]
             [:div
              [:div.schedule-days.toggle-group
               (doall
                (for [{:keys [num label-key]} rotated-days]
                  ^{:key num}
                  [:button.toggle-option
                   {:class (when (contains? active-days num) "active")
                    :on-click (fn [_]
                                (reset! schedule-days num))}
                   (t label-key)]))]
              (when (:date next-date)
                [:div.schedule-next-date-row
                 [:span.schedule-next-date (str (t :scheduling/next-meeting) " " (:date next-date))]])
              [:div.schedule-offset-row
               [:label
                [:input {:type "checkbox"
                         :checked @biweekly-offset
                         :on-change (fn [_] (swap! biweekly-offset not))}]
                (str " " (t :scheduling/offset-week))]]
              [:div.schedule-time-row
               [:label (t :scheduling/time)]
               [schedule-time-picker
                @shared-time-val
                (fn [v]
                  (reset! shared-time-val v)
                  (sync-schedule-time!))]]])

           [:div
            [:div.schedule-days.toggle-group
             (doall
              (for [{:keys [num label-key]} day-keys]
                ^{:key num}
                [:button.toggle-option
                 {:class (when (contains? active-days num) "active")
                  :on-click (fn [_]
                              (let [new-days (if (contains? active-days num)
                                               (disj active-days num)
                                               (conj active-days num))]
                                (reset! schedule-days (serialize-schedule-days new-days))))}
                 (t label-key)]))]
            [:div.schedule-per-day-row
             [:label
              [:input {:type "checkbox"
                       :checked @per-day?
                       :on-change (fn [_]
                                    (swap! per-day? not)
                                    (sync-schedule-time!))}]
              (str " " (t :scheduling/per-day))]]
            (if @per-day?
              [:div.schedule-per-day-times
               (doall
                (for [{:keys [num label-key]} day-keys
                      :when (contains? active-days num)]
                  ^{:key num}
                  [:div.schedule-day-time-row
                   [:span.schedule-day-label (t label-key)]
                   [schedule-time-picker
                    (get @day-times num "")
                    (fn [v]
                      (swap! day-times assoc num v)
                      (sync-schedule-time!))]]))]
              [:div.schedule-time-row
               [:label (t :scheduling/time)]
               [schedule-time-picker
                @shared-time-val
                (fn [v]
                  (reset! shared-time-val v)
                  (sync-schedule-time!))]])])]))))

(defn edit-item-modal []
  (let [fields-state (r/atom nil)
        prev-entity (r/atom nil)
        active-tab (r/atom :edit)]
    (fn []
      (if-let [{:keys [type entity tab]} (:editing-modal @state/*app-state)]
        (do
          (when (not= entity @prev-entity)
            (reset! prev-entity entity)
            (reset! fields-state (edit-modal-fields {:type type :entity entity}))
            (reset! active-tab (or tab :edit)))
          (when-let [{:keys [title description tags link badge-title schedule-days schedule-time schedule-mode biweekly-offset]} @fields-state]
            (let [is-category (not (#{:task :meet :meeting-series :recurring-task :resource} type))
                  preview-tab-key (case type
                                    (:task :recurring-task) :modal/tab-task
                                    (:meet :meeting-series) :modal/tab-meet
                                    :resource :modal/tab-resource
                                    :modal/tab-category)]
              [:div.modal-overlay
               [:div.modal.edit-item-modal {:on-click #(.stopPropagation %)}
                [:div.modal-body
                 [:div.edit-modal-tabs
                  [:button {:class (when (= @active-tab :preview) "active")
                            :on-click #(do (reset! active-tab :preview)
                                           (when-let [path (url/entity->path {:type type :entity entity})]
                                             (url/replace-state! path)))}
                   (t preview-tab-key)]
                  [:button {:class (when (= @active-tab :edit) "active")
                            :on-click #(do (reset! active-tab :edit)
                                           (when-let [path (url/entity->path {:type type :entity entity})]
                                             (url/replace-state! (str path "?section=edit"))))}
                   (t :modal/edit)]
                  (when (#{:meeting-series :recurring-task} type)
                    [:button {:class (when (= @active-tab :scheduling) "active")
                              :on-click #(reset! active-tab :scheduling)}
                     (t :modal/tab-scheduling)])]
                 (case @active-tab
                   :scheduling
                   [scheduling-tab-content entity schedule-days schedule-time schedule-mode biweekly-offset]

                   :preview
                   [:div.edit-modal-preview
                    [:h2.preview-title @title]
                    (when (and link (seq @link))
                      [:a.preview-link {:href @link :target "_blank" :rel "noopener noreferrer"} @link])
                    [markdown-preview @description]]
                   [:div.item-edit-form
                    [:input {:type "text"
                             :value @title
                             :on-change #(reset! title (-> % .-target .-value))
                             :placeholder (if is-category (t :category/name-placeholder) (t :task/title-placeholder))}]
                    (when link
                      [:input {:type "text"
                               :value @link
                               :on-change #(reset! link (-> % .-target .-value))
                               :placeholder (t :resources/link-placeholder)}])
                    (when badge-title
                      [:input {:type "text"
                               :value @badge-title
                               :on-change #(reset! badge-title (-> % .-target .-value))
                               :placeholder (t :category/badge-title-placeholder)}])
                    [:input {:type "text"
                             :value @tags
                             :on-change #(reset! tags (-> % .-target .-value))
                             :placeholder (t :task/tags-placeholder)}]
                    (if (state/vim-keys?)
                      [cm-textarea {:value description
                                    :on-change #(reset! description %)
                                    :placeholder (t :task/description-placeholder)
                                    :rows (if (#{:meet :meeting-series :recurring-task} type) 20 3)}]
                      [:textarea {:value @description
                                  :on-change #(reset! description (-> % .-target .-value))
                                  :placeholder (t :task/description-placeholder)
                                  :rows (if (#{:meet :meeting-series :recurring-task} type) 20 3)}])])]
                [:div.modal-footer
                 (when is-category
                   (let [category-type (subs (name type) 9)]
                     [:button.confirm-delete
                      {:on-click #(do (state/clear-editing-modal)
                                      (state/set-confirm-delete-category category-type entity))}
                      (t :category/delete)]))
                 [:button.cancel {:on-click #(state/clear-editing-modal)} (t :modal/cancel)]
                 [:button.confirm {:on-click #(edit-modal-save @fields-state)} (t :task/save)]]]])))
        (reset! prev-entity nil)))))
