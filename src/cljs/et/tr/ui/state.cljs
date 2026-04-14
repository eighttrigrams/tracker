(ns et.tr.ui.state
  (:require [clojure.set]
            [clojure.string]
            [reagent.core :as r]
            [et.tr.ui.constants :as constants]
            [et.tr.ui.url :as url]
            [et.tr.ui.api :as api]
            [et.tr.ui.scheduling :as scheduling]
            [et.tr.ui.state.auth :as auth]
            [et.tr.ui.state.mail :as mail]
            [et.tr.ui.state.users :as users]
            [et.tr.ui.state.categories :as categories]
            [et.tr.ui.state.tasks :as tasks]
            [et.tr.ui.state.tasks-page :as tasks-page]
            [et.tr.ui.state.today-page :as today-page]
            [et.tr.ui.state.resources :as resources-state]
            [et.tr.ui.state.meets :as meets-state]
            [et.tr.ui.state.meeting-series :as meeting-series-state]
            [et.tr.ui.state.recurring-tasks :as recurring-tasks-state]
            [et.tr.ui.state.journals :as journals-state]
            [et.tr.ui.state.journal-entries :as journal-entries-state]
            [et.tr.ui.state.relations :as relations-state]
            [et.tr.ui.state.ui :as ui]))

(def ^:const CATEGORY-TYPE-PERSON constants/CATEGORY-TYPE-PERSON)
(def ^:const CATEGORY-TYPE-PLACE constants/CATEGORY-TYPE-PLACE)
(def ^:const CATEGORY-TYPE-PROJECT constants/CATEGORY-TYPE-PROJECT)
(def ^:const CATEGORY-TYPE-GOAL constants/CATEGORY-TYPE-GOAL)

(def initial-collection-state
  {:tasks []
   :people []
   :places []
   :projects []
   :goals []
   :messages []
   :resources []
   :meets []
   :meeting-series []
   :recurring-tasks []
   :journals []
   :journal-entries []
   :today-meets []
   :today-journal-entries []
   :upcoming-horizon nil})

(defonce *app-state (r/atom {;; Data collections
                            :tasks []
                            :people []
                            :places []
                            :projects []
                            :goals []
                            :messages []
                            :resources []
                            :meets []
                            :meeting-series []
                            :recurring-tasks []
                            :journals []
                            :journal-entries []
                            :today-journal-entries []
                            :users []
                            :available-users []

                            ;; Shared category filters (between tasks and resources)
                            :shared/filter-people #{}
                            :shared/filter-places #{}
                            :shared/filter-projects #{}

                            ;; Tasks page state
                            :tasks-page/filter-goals #{}
                            :tasks-page/filter-search ""
                            :tasks-page/category-search {:people "" :places "" :projects "" :goals ""}
                            :tasks-page/importance-filter nil
                            :tasks-page/collapsed-filters #{:people :places :projects :goals}
                            :tasks-page/expanded-task nil
                            :editing-task nil
                            :pending-new-item nil
                            :editing-modal nil
                            :confirm-delete-task nil
                            :confirm-undone-task nil

                            ;; Today meets
                            :today-meets []

                            ;; Today page state
                            :today-page/excluded-places #{}
                            :today-page/excluded-projects #{}
                            :today-page/collapsed-filters #{:places :projects}
                            :today-page/category-search {:places "" :projects ""}
                            :today-page/expanded-task nil
                            :today-page/expanded-meet nil
                            :today-page/selected-view :urgent
                            :today-page/confirm-move-to-today nil
                            :today-page/selected-day 0
                            :upcoming-horizon nil

                            ;; Resources page state
                            :resources-page/filter-goals #{}
                            :resources-page/collapsed-filters #{:people :places :projects :goals}
                            :resources-page/category-search {:people "" :places "" :projects "" :goals ""}

                            ;; Tasks page recurring mode
                            :tasks-page/recurring-mode false
                            :tasks-page/filter-recurring nil

                            ;; Resources page journals mode
                            :resources-page/journals-mode false
                            :resources-page/filter-journal nil

                            ;; Today page journals mode
                            :today-page/journals-mode false

                            ;; Meets page state
                            :meets-page/series-mode false
                            :meets-page/filter-series nil
                            :meets-page/filter-goals #{}
                            :meets-page/collapsed-filters #{:people :places :projects :goals}
                            :meets-page/category-search {:people "" :places "" :projects "" :goals ""}

                            ;; Task dropdown state
                            :task-dropdown-open nil

                            ;; Category selector state
                            :category-selector/open nil
                            :category-selector/search ""

                            ;; Global UI state
                            :active-tab :today
                            :sort-mode :today
                            :tasks-page/last-sort-mode :recent
                            :drag-task nil
                            :drag-over-task nil
                            :drag-over-urgency-section nil
                            :drag-category nil
                            :drag-over-category nil
                            :category-page/editing nil
                            :show-user-switcher false
                            :work-private-mode :both
                            :strict-mode false
                            :dark-mode false
                            :error nil

                            ;; Auth state
                            :auth-required? nil
                            :logged-in? false
                            :token nil
                            :current-user nil
                            :confirm-delete-user nil}))

(defn auth-headers []
  (let [token (:token @*app-state)
        current-user (:current-user @*app-state)
        user-id (:id current-user)]
    (cond-> {}
      token (assoc "Authorization" (str "Bearer " token))
      (and (not token) current-user) (assoc "X-User-Id" (if (nil? user-id) "null" (str user-id))))))

(defn clear-error []
  (swap! *app-state assoc :error nil))

(defn is-admin? []
  (:is_admin (:current-user @*app-state)))

(defn has-mail? []
  (:has_mail (:current-user @*app-state)))

(defn- current-scope []
  (name (:work-private-mode @*app-state)))

(declare fetch-tasks)
(declare fetch-today-meets)
(declare fetch-messages)
(declare fetch-resources)
(declare fetch-resources-or-journals)
(declare fetch-meets)
(declare fetch-users)
(declare fetch-people)
(declare fetch-places)
(declare fetch-projects)
(declare fetch-goals)

(defn- fetch-all [user]
  (if (:is_admin user)
    (do
      (fetch-users)
      (swap! *app-state assoc :active-tab :users))
    (do
      (swap! *app-state assoc :active-tab :today)
      (fetch-tasks)
      (fetch-people)
      (fetch-places)
      (fetch-projects)
      (fetch-goals)
      (when (:has_mail user)
        (fetch-messages)))))

(defn- restore-from-url []
  (when-let [{:keys [type api-path]} (url/parse-item-path (.-pathname js/location))]
    (let [section (-> (js/URLSearchParams. (.-search js/location)) (.get "section"))
          tab (if (= section "edit") :edit :preview)]
      (api/fetch-json api-path (auth-headers)
        (fn [entity]
          (swap! *app-state assoc :editing-modal {:type type :entity entity :tab tab}))))))

(defn fetch-auth-required []
  (auth/fetch-auth-required *app-state auth-headers initial-collection-state
    (fn [user]
      (fetch-all user)
      (restore-from-url))
    :on-skip-logins #(users/fetch-available-users *app-state)))

(defn login [username password on-success]
  (auth/login *app-state username password
    (fn []
      (fetch-all (:current-user @*app-state))
      (when on-success (on-success)))))

(defn logout []
  (auth/logout *app-state initial-collection-state))

(defn update-user-language [language]
  (auth/update-user-language *app-state auth-headers language))

(defn update-vim-keys [enabled]
  (auth/update-vim-keys *app-state auth-headers enabled))

(defn vim-keys? []
  (= 1 (:vim_keys (:current-user @*app-state))))

(defn fetch-messages []
  (mail/fetch-messages *app-state auth-headers))

(defn set-mail-sort-mode [mode]
  (mail/set-mail-sort-mode *app-state auth-headers mode))

(defn set-expanded-message [id]
  (mail/set-expanded-message id))

(defn set-message-done [message-id done?]
  (mail/set-message-done *app-state auth-headers message-id done?))

(defn set-confirm-delete-message [message]
  (mail/set-confirm-delete-message message))

(defn clear-confirm-delete-message []
  (mail/clear-confirm-delete-message))

(defn delete-message [message-id]
  (mail/delete-message *app-state auth-headers message-id))

(defn set-mail-sender-filter [sender]
  (mail/set-mail-sender-filter *app-state auth-headers sender))

(defn clear-mail-sender-filter []
  (mail/clear-mail-sender-filter *app-state auth-headers))

(defn toggle-excluded-sender [sender]
  (mail/toggle-excluded-sender *app-state auth-headers sender))

(defn clear-excluded-sender [sender]
  (mail/clear-excluded-sender *app-state auth-headers sender))

(defn clear-all-mail-filters []
  (mail/clear-all-mail-filters *app-state auth-headers))

(defn set-editing-message [id]
  (mail/set-editing-message id))

(defn clear-editing-message []
  (mail/clear-editing-message))

(defn set-message-importance [message-id importance]
  (mail/set-message-importance *app-state auth-headers message-id importance))

(defn set-message-importance-filter [level]
  (mail/set-importance-filter fetch-messages level))

(defn set-message-urgency [message-id urgency]
  (mail/set-message-urgency *app-state auth-headers message-id urgency))

(defn set-message-urgency-filter [level]
  (mail/set-urgency-filter fetch-messages level))

(defn set-message-scope [message-id scope]
  (mail/set-message-scope *app-state auth-headers message-id scope))

(defn update-message-annotation [message-id annotation]
  (mail/update-message-annotation *app-state auth-headers message-id annotation))

(defn add-message [title on-success]
  (mail/add-message *app-state auth-headers title on-success))

(defn set-message-dropdown-open [message-id]
  (mail/set-message-dropdown-open message-id))

(defn set-message-action-dropdown-open [message-id]
  (mail/set-message-action-dropdown-open message-id))

(defn merge-message-with-below [message-id target-id]
  (mail/merge-message-with-below *app-state auth-headers message-id target-id))

(defn convert-message-to-resource [message-id link]
  (mail/convert-message-to-resource *app-state auth-headers message-id link))

(defn convert-message-to-task [message-id]
  (mail/convert-message-to-task *app-state auth-headers message-id))

(defn set-confirm-delete-all-archived [val]
  (mail/set-confirm-delete-all-archived val))

(defn delete-all-archived []
  (mail/delete-all-archived *app-state auth-headers))

(declare has-active-shared-filters?)
(declare set-pending-new-item)

(defn- resources-fetch-opts []
  {:search-term (:filter-search @resources-state/*resources-page-state)
   :importance (:importance-filter @resources-state/*resources-page-state)
   :domain (:domain-filter @resources-state/*resources-page-state)
   :sort-mode (:sort-mode @resources-state/*resources-page-state)
   :context (:work-private-mode @*app-state)
   :strict (:strict-mode @*app-state)
   :filter-people (:shared/filter-people @*app-state)
   :filter-places (:shared/filter-places @*app-state)
   :filter-projects (:shared/filter-projects @*app-state)
   :filter-goals (:resources-page/filter-goals @*app-state)})

(defn fetch-resources
  ([] (fetch-resources (resources-fetch-opts)))
  ([opts]
   (resources-state/fetch-resources *app-state auth-headers opts)))

(defn add-resource [title link on-success]
  (if (has-active-shared-filters?)
    (set-pending-new-item :resource title on-success {:link link})
    (resources-state/add-resource *app-state auth-headers current-scope title link on-success fetch-resources)))

(defn update-resource [resource-id title link description tags on-success]
  (resources-state/update-resource *app-state auth-headers resource-id title link description tags on-success))

(defn delete-resource [resource-id]
  (resources-state/delete-resource *app-state auth-headers resource-id))

(defn set-resource-scope [resource-id scope]
  (resources-state/set-resource-scope *app-state auth-headers resource-id scope))

(defn set-resource-importance [resource-id importance]
  (resources-state/set-resource-importance *app-state auth-headers resource-id importance))

(defn set-expanded-resource [id]
  (resources-state/set-expanded-resource id))

(defn set-editing-resource [id]
  (resources-state/set-editing-resource id))

(defn clear-editing-resource []
  (resources-state/clear-editing-resource))

(defn set-confirm-delete-resource [resource]
  (resources-state/set-confirm-delete-resource resource))

(defn clear-confirm-delete-resource []
  (resources-state/clear-confirm-delete-resource))

(defn set-resource-filter-search [search-term]
  (resources-state/set-filter-search fetch-resources search-term))

(defn set-resource-sort-mode [mode]
  (resources-state/set-sort-mode fetch-resources mode))

(defn set-drag-resource [resource-id]
  (resources-state/set-drag-resource *app-state resource-id))

(defn set-drag-over-resource [resource-id]
  (resources-state/set-drag-over-resource *app-state resource-id))

(defn clear-resource-drag-state []
  (resources-state/clear-resource-drag-state *app-state))

(defn reorder-resource [resource-id target-resource-id position]
  (resources-state/reorder-resource *app-state auth-headers fetch-resources resource-id target-resource-id position))

(defn set-resource-importance-filter [level]
  (resources-state/set-importance-filter fetch-resources level))

(defn set-resource-domain-filter [domain]
  (resources-state/set-domain-filter fetch-resources domain))

(defn clear-resource-domain-filter []
  (resources-state/clear-domain-filter fetch-resources))

(defn clear-all-resource-filters []
  (resources-state/clear-all-resource-filters fetch-resources))

(defn categorize-resource [resource-id category-type category-id]
  (resources-state/categorize-resource *app-state auth-headers fetch-resources resource-id category-type category-id))

(defn uncategorize-resource [resource-id category-type category-id]
  (resources-state/uncategorize-resource *app-state auth-headers fetch-resources resource-id category-type category-id))

(defn- meets-fetch-opts []
  (let [series-filter (:meets-page/filter-series @*app-state)]
    (cond-> {:search-term (:filter-search @meets-state/*meets-page-state)
             :importance (:importance-filter @meets-state/*meets-page-state)
             :sort-mode (:sort-mode @meets-state/*meets-page-state)
             :context (:work-private-mode @*app-state)
             :strict (:strict-mode @*app-state)
             :filter-people (:shared/filter-people @*app-state)
             :filter-places (:shared/filter-places @*app-state)
             :filter-projects (:shared/filter-projects @*app-state)
             :filter-goals (:meets-page/filter-goals @*app-state)}
      series-filter (assoc :series-id (:id series-filter)))))

(defn fetch-meets
  ([] (fetch-meets (meets-fetch-opts)))
  ([opts]
   (meets-state/fetch-meets *app-state auth-headers opts)))

(defn add-meet [title on-success]
  (if (has-active-shared-filters?)
    (set-pending-new-item :meet title on-success)
    (meets-state/add-meet *app-state auth-headers current-scope title on-success fetch-meets)))

(defn update-meet [meet-id title description tags on-success]
  (meets-state/update-meet *app-state auth-headers meet-id title description tags on-success))

(defn delete-meet [meet-id]
  (meets-state/delete-meet *app-state auth-headers meet-id))

(defn set-meet-scope [meet-id scope]
  (meets-state/set-meet-scope *app-state auth-headers meet-id scope))

(defn set-meet-importance [meet-id importance]
  (meets-state/set-meet-importance *app-state auth-headers meet-id importance))

(defn set-meet-start-date [meet-id start-date]
  (meets-state/set-meet-start-date *app-state auth-headers fetch-meets meet-id start-date))

(defn archive-meet [meet-id]
  (meets-state/archive-meet *app-state auth-headers fetch-today-meets meet-id))

(defn set-meet-start-time [meet-id start-time]
  (meets-state/set-meet-start-time *app-state auth-headers fetch-meets meet-id start-time))

(defn set-meets-sort-mode [mode]
  (meets-state/set-sort-mode fetch-meets mode))

(defn set-expanded-meet [id]
  (meets-state/set-expanded-meet id))

(defn set-editing-meet [id]
  (meets-state/set-editing-meet id))

(defn clear-editing-meet []
  (meets-state/clear-editing-meet))

(defn set-confirm-delete-meet [meet]
  (meets-state/set-confirm-delete-meet meet))

(defn clear-confirm-delete-meet []
  (meets-state/clear-confirm-delete-meet))

(defn set-meet-filter-search [search-term]
  (meets-state/set-filter-search fetch-meets search-term))

(defn set-meet-importance-filter [level]
  (meets-state/set-importance-filter fetch-meets level))

(defn clear-all-meet-filters []
  (meets-state/clear-all-meet-filters fetch-meets))

(defn categorize-meet [meet-id category-type category-id]
  (meets-state/categorize-meet *app-state auth-headers fetch-meets meet-id category-type category-id))

(defn uncategorize-meet [meet-id category-type category-id]
  (meets-state/uncategorize-meet *app-state auth-headers fetch-meets meet-id category-type category-id))

(declare fetch-meeting-series)

(defn- meeting-series-fetch-opts []
  {:search-term (:filter-search @meeting-series-state/*meeting-series-page-state)
   :context (:work-private-mode @*app-state)
   :strict (:strict-mode @*app-state)
   :filter-people (:shared/filter-people @*app-state)
   :filter-places (:shared/filter-places @*app-state)
   :filter-projects (:shared/filter-projects @*app-state)
   :filter-goals (:meets-page/filter-goals @*app-state)})

(defn fetch-meeting-series
  ([] (fetch-meeting-series (meeting-series-fetch-opts)))
  ([opts]
   (meeting-series-state/fetch-meeting-series *app-state auth-headers opts)))

(defn add-meeting-series [title on-success]
  (if (has-active-shared-filters?)
    (set-pending-new-item :meeting-series title on-success)
    (meeting-series-state/add-meeting-series *app-state auth-headers current-scope title on-success fetch-meeting-series)))

(defn update-meeting-series [series-id title description tags on-success]
  (meeting-series-state/update-meeting-series *app-state auth-headers series-id title description tags on-success))

(defn delete-meeting-series [series-id]
  (meeting-series-state/delete-meeting-series *app-state auth-headers series-id))

(defn set-meeting-series-scope [series-id scope]
  (meeting-series-state/set-meeting-series-scope *app-state auth-headers series-id scope))

(defn set-expanded-series [id]
  (meeting-series-state/set-expanded-series id))

(defn set-editing-series [id]
  (meeting-series-state/set-editing-series id))

(defn clear-editing-series []
  (meeting-series-state/clear-editing-series))

(defn set-confirm-delete-series [series]
  (meeting-series-state/set-confirm-delete-series series))

(defn clear-confirm-delete-series []
  (meeting-series-state/clear-confirm-delete-series))

(defn set-meeting-series-schedule [series-id schedule-days schedule-time schedule-mode biweekly-offset on-success]
  (meeting-series-state/set-meeting-series-schedule *app-state auth-headers series-id schedule-days schedule-time schedule-mode biweekly-offset on-success))

(defn create-meeting-for-series [series-id date time]
  (meeting-series-state/create-meeting-for-series *app-state auth-headers fetch-meeting-series series-id date time))

(defn create-meeting-for-series-with-notification [series-id date time on-success]
  (meeting-series-state/create-meeting-for-series *app-state auth-headers
    (fn []
      (fetch-meeting-series)
      (fetch-today-meets))
    series-id date time on-success))

(def get-schedule-time-for-day scheduling/get-schedule-time-for-day)
(def next-biweekly-date-from scheduling/next-biweekly-date-from)
(def next-scheduled-date-for-mode scheduling/next-scheduled-date-for-mode)
(def tomorrow-str scheduling/tomorrow-str)

(defn next-meeting-action [series]
  (scheduling/next-scheduled-action series {:has-today-key :has_today_meet :has-future-key :has_future_meet}))

(defn next-recurring-task-action [rtask]
  (scheduling/next-scheduled-action rtask {:has-today-key :has_today_task :has-future-key :has_future_task}))

(defn set-meeting-series-filter-search [search-term]
  (meeting-series-state/set-filter-search fetch-meeting-series search-term))

(defn clear-all-meeting-series-filters []
  (meeting-series-state/clear-all-meeting-series-filters fetch-meeting-series))

(defn categorize-meeting-series [series-id category-type category-id]
  (meeting-series-state/categorize-meeting-series *app-state auth-headers fetch-meeting-series series-id category-type category-id))

(defn uncategorize-meeting-series [series-id category-type category-id]
  (meeting-series-state/uncategorize-meeting-series *app-state auth-headers fetch-meeting-series series-id category-type category-id))

(defn add-meeting-series-with-categories [title categories on-success]
  (meeting-series-state/add-meeting-series-with-categories *app-state auth-headers fetch-meeting-series current-scope title categories on-success))

(declare fetch-recurring-tasks)

(defn- recurring-tasks-fetch-opts []
  {:search-term (:filter-search @recurring-tasks-state/*recurring-tasks-page-state)
   :context (:work-private-mode @*app-state)
   :strict (:strict-mode @*app-state)
   :filter-people (:shared/filter-people @*app-state)
   :filter-places (:shared/filter-places @*app-state)
   :filter-projects (:shared/filter-projects @*app-state)
   :filter-goals (:tasks-page/filter-goals @*app-state)})

(defn fetch-recurring-tasks
  ([] (fetch-recurring-tasks (recurring-tasks-fetch-opts)))
  ([opts]
   (recurring-tasks-state/fetch-recurring-tasks *app-state auth-headers opts)))

(defn add-recurring-task [title on-success]
  (if (has-active-shared-filters?)
    (set-pending-new-item :recurring-task title on-success)
    (recurring-tasks-state/add-recurring-task *app-state auth-headers current-scope title on-success fetch-recurring-tasks)))

(defn update-recurring-task [rtask-id title description tags on-success]
  (recurring-tasks-state/update-recurring-task *app-state auth-headers rtask-id title description tags on-success))

(defn delete-recurring-task [rtask-id]
  (recurring-tasks-state/delete-recurring-task *app-state auth-headers rtask-id))

(defn set-recurring-task-scope [rtask-id scope]
  (recurring-tasks-state/set-recurring-task-scope *app-state auth-headers rtask-id scope))

(defn set-expanded-rtask [id]
  (recurring-tasks-state/set-expanded-rtask id))

(defn set-confirm-delete-rtask [rtask]
  (recurring-tasks-state/set-confirm-delete-rtask rtask))

(defn clear-confirm-delete-rtask []
  (recurring-tasks-state/clear-confirm-delete-rtask))

(defn set-recurring-task-schedule [rtask-id schedule-days schedule-time schedule-mode biweekly-offset task-type on-success]
  (recurring-tasks-state/set-recurring-task-schedule *app-state auth-headers rtask-id schedule-days schedule-time schedule-mode biweekly-offset task-type on-success))

(defn create-task-for-recurring [rtask-id date time]
  (recurring-tasks-state/create-task-for-recurring *app-state auth-headers fetch-recurring-tasks rtask-id date time))

(defn set-recurring-task-filter-search [search-term]
  (recurring-tasks-state/set-filter-search fetch-recurring-tasks search-term))

(defn categorize-recurring-task [rtask-id category-type category-id]
  (recurring-tasks-state/categorize-recurring-task *app-state auth-headers fetch-recurring-tasks rtask-id category-type category-id))

(defn uncategorize-recurring-task [rtask-id category-type category-id]
  (recurring-tasks-state/uncategorize-recurring-task *app-state auth-headers fetch-recurring-tasks rtask-id category-type category-id))

(defn add-recurring-task-with-categories [title categories on-success]
  (recurring-tasks-state/add-recurring-task-with-categories *app-state auth-headers fetch-recurring-tasks current-scope title categories on-success))

(declare fetch-journals)
(declare fetch-journal-entries)
(declare fetch-today-journal-entries)

(defn- journals-fetch-opts []
  {:search-term (:filter-search @journals-state/*journals-page-state)
   :context (:work-private-mode @*app-state)
   :strict (:strict-mode @*app-state)
   :filter-people (:shared/filter-people @*app-state)
   :filter-places (:shared/filter-places @*app-state)
   :filter-projects (:shared/filter-projects @*app-state)
   :filter-goals (:resources-page/filter-goals @*app-state)})

(defn fetch-journals
  ([] (fetch-journals (journals-fetch-opts)))
  ([opts]
   (journals-state/fetch-journals *app-state auth-headers opts)))

(defn add-journal [title schedule-type on-success]
  (journals-state/add-journal *app-state auth-headers current-scope title schedule-type on-success fetch-journals))

(defn update-journal [journal-id title description tags on-success]
  (journals-state/update-journal *app-state auth-headers journal-id title description tags on-success))

(defn delete-journal [journal-id]
  (journals-state/delete-journal *app-state auth-headers journal-id))

(defn set-journal-scope [journal-id scope]
  (journals-state/set-journal-scope *app-state auth-headers journal-id scope))

(defn set-expanded-journal [id]
  (journals-state/set-expanded-journal id))

(defn set-confirm-delete-journal [journal]
  (journals-state/set-confirm-delete-journal journal))

(defn clear-confirm-delete-journal []
  (journals-state/clear-confirm-delete-journal))

(defn set-journal-filter-search [search-term]
  (journals-state/set-filter-search fetch-journals search-term))

(defn categorize-journal [journal-id category-type category-id]
  (journals-state/categorize-journal *app-state auth-headers fetch-journals journal-id category-type category-id))

(defn uncategorize-journal [journal-id category-type category-id]
  (journals-state/uncategorize-journal *app-state auth-headers fetch-journals journal-id category-type category-id))

(defn- journal-entries-fetch-opts []
  (let [journal-filter (:resources-page/filter-journal @*app-state)]
    {:sort-mode :added
     :context (:work-private-mode @*app-state)
     :strict (:strict-mode @*app-state)
     :filter-people (:shared/filter-people @*app-state)
     :filter-places (:shared/filter-places @*app-state)
     :filter-projects (:shared/filter-projects @*app-state)
     :filter-goals (:resources-page/filter-goals @*app-state)
     :journal-id (when journal-filter (:id journal-filter))}))

(defn fetch-journal-entries
  ([] (fetch-journal-entries (journal-entries-fetch-opts)))
  ([opts]
   (journal-entries-state/fetch-journal-entries *app-state auth-headers opts)))

(defn fetch-today-journal-entries
  ([] (fetch-today-journal-entries {:context (:work-private-mode @*app-state)
                                    :strict (:strict-mode @*app-state)}))
  ([opts]
   (journal-entries-state/fetch-today-journal-entries *app-state auth-headers opts)))

(defn add-journal-entry [title on-success]
  (journal-entries-state/add-journal-entry *app-state auth-headers current-scope title on-success fetch-journal-entries))

(defn update-journal-entry [entry-id title description tags on-success]
  (journal-entries-state/update-journal-entry *app-state auth-headers entry-id title description tags on-success))

(defn delete-journal-entry [entry-id]
  (journal-entries-state/delete-journal-entry *app-state auth-headers entry-id))

(defn set-journal-entry-scope [entry-id scope]
  (journal-entries-state/set-journal-entry-scope *app-state auth-headers entry-id scope))

(defn set-journal-entry-importance [entry-id importance]
  (journal-entries-state/set-journal-entry-importance *app-state auth-headers entry-id importance))

(defn set-expanded-journal-entry [id]
  (journal-entries-state/set-expanded-entry id))

(defn set-confirm-delete-journal-entry [entry]
  (journal-entries-state/set-confirm-delete-entry entry))

(defn clear-confirm-delete-journal-entry []
  (journal-entries-state/clear-confirm-delete-entry))

(defn set-journal-entry-filter-search [search-term]
  (journal-entries-state/set-filter-search fetch-journal-entries search-term))

(defn set-journal-entry-importance-filter [level]
  (journal-entries-state/set-importance-filter fetch-journal-entries level))

(defn categorize-journal-entry [entry-id category-type category-id]
  (journal-entries-state/categorize-journal-entry *app-state auth-headers fetch-journal-entries entry-id category-type category-id))

(defn uncategorize-journal-entry [entry-id category-type category-id]
  (journal-entries-state/uncategorize-journal-entry *app-state auth-headers fetch-journal-entries entry-id category-type category-id))

(defn toggle-journals-mode []
  (swap! *app-state (fn [s] (-> s
                                (update :resources-page/journals-mode not)
                                (assoc :resources-page/filter-journal nil))))
  (if (:resources-page/journals-mode @*app-state)
    (fetch-journals)
    (fetch-resources)))

(defn journals-mode? []
  (:resources-page/journals-mode @*app-state))

(defn set-journal-filter [journal]
  (swap! *app-state assoc
         :resources-page/filter-journal {:id (:id journal) :title (:title journal)}
         :resources-page/journals-mode false)
  (fetch-journal-entries))

(defn clear-journal-filter []
  (swap! *app-state assoc :resources-page/filter-journal nil)
  (fetch-journal-entries))

(defn journal-filter []
  (:resources-page/filter-journal @*app-state))

(defn toggle-today-journals-mode []
  (let [new-mode (not (:today-page/journals-mode @*app-state))]
    (swap! *app-state assoc :today-page/journals-mode new-mode)
    (when new-mode
      (fetch-today-journal-entries))))

(defn today-journals-mode? []
  (:today-page/journals-mode @*app-state))

(defn toggle-recurring-mode []
  (swap! *app-state (fn [s] (-> s
                                (update :tasks-page/recurring-mode not)
                                (assoc :tasks-page/filter-recurring nil))))
  (if (:tasks-page/recurring-mode @*app-state)
    (fetch-recurring-tasks)
    (fetch-tasks)))

(defn recurring-mode? []
  (:tasks-page/recurring-mode @*app-state))

(defn set-recurring-filter [rtask]
  (swap! *app-state assoc
         :tasks-page/filter-recurring {:id (:id rtask) :title (:title rtask)}
         :tasks-page/recurring-mode false)
  (fetch-tasks))

(defn clear-recurring-filter []
  (swap! *app-state assoc :tasks-page/filter-recurring nil)
  (fetch-tasks))

(defn recurring-filter []
  (:tasks-page/filter-recurring @*app-state))

(defn toggle-series-mode []
  (swap! *app-state (fn [s] (-> s
                                (update :meets-page/series-mode not)
                                (assoc :meets-page/filter-series nil))))
  (if (:meets-page/series-mode @*app-state)
    (fetch-meeting-series)
    (fetch-meets)))

(defn series-mode? []
  (:meets-page/series-mode @*app-state))

(defn set-series-filter [series]
  (swap! *app-state assoc
         :meets-page/filter-series {:id (:id series) :title (:title series)}
         :meets-page/series-mode false)
  (fetch-meets))

(defn clear-series-filter []
  (swap! *app-state assoc :meets-page/filter-series nil)
  (fetch-meets))

(defn series-filter []
  (:meets-page/filter-series @*app-state))

(defn toggle-meets-filter-collapsed [filter-key]
  (let [was-collapsed (contains? (:meets-page/collapsed-filters @*app-state) filter-key)
        all-filters #{:people :places :projects :goals}]
    (swap! *app-state update :meets-page/collapsed-filters
           (fn [collapsed]
             (if (contains? collapsed filter-key)
               (disj all-filters filter-key)
               (conj collapsed filter-key))))
    (when was-collapsed
      (swap! *app-state update :meets-page/category-search
             (fn [searches]
               (reduce #(assoc %1 %2 "") searches all-filters))))
    (js/setTimeout
     (fn []
       (when-let [el (.getElementById js/document
                                      (if was-collapsed
                                        (str "meets-filter-" (name filter-key))
                                        "meets-filter-search"))]
         (.focus el)))
     0)))

(defn toggle-meets-goal-filter [id]
  (swap! *app-state update :meets-page/filter-goals
         #(if (contains? % id) (disj % id) (conj % id)))
  (if (:meets-page/series-mode @*app-state)
    (fetch-meeting-series)
    (fetch-meets)))

(defn clear-meets-goal-filter []
  (swap! *app-state assoc :meets-page/filter-goals #{})
  (if (:meets-page/series-mode @*app-state)
    (fetch-meeting-series)
    (fetch-meets)))

(defn has-meets-goal-filter? []
  (seq (:meets-page/filter-goals @*app-state)))

(defn set-meets-category-search [category-key search-term]
  (swap! *app-state assoc-in [:meets-page/category-search category-key] search-term))

(defn clear-uncollapsed-meet-filters []
  (let [collapsed (:meets-page/collapsed-filters @*app-state)
        all-filters #{:people :places :projects :goals}
        uncollapsed (clojure.set/difference all-filters collapsed)]
    (if (empty? uncollapsed)
      (swap! *app-state assoc
             :shared/filter-people #{}
             :shared/filter-places #{}
             :shared/filter-projects #{}
             :meets-page/filter-goals #{}
             :meets-page/category-search {:people "" :places "" :projects "" :goals ""})
      (do
        (doseq [filter-key uncollapsed]
          (case filter-key
            :people (swap! *app-state assoc :shared/filter-people #{})
            :places (swap! *app-state assoc :shared/filter-places #{})
            :projects (swap! *app-state assoc :shared/filter-projects #{})
            :goals (swap! *app-state assoc :meets-page/filter-goals #{})))
        (swap! *app-state assoc
               :meets-page/collapsed-filters all-filters
               :meets-page/category-search {:people "" :places "" :projects "" :goals ""})))
    (if (:meets-page/series-mode @*app-state)
      (fetch-meeting-series)
      (do (meets-state/set-importance-filter fetch-meets nil)
          (fetch-meets)))))

(defn toggle-resources-filter-collapsed [filter-key]
  (let [was-collapsed (contains? (:resources-page/collapsed-filters @*app-state) filter-key)
        all-filters #{:people :places :projects :goals}]
    (swap! *app-state update :resources-page/collapsed-filters
           (fn [collapsed]
             (if (contains? collapsed filter-key)
               (disj all-filters filter-key)
               (conj collapsed filter-key))))
    (when was-collapsed
      (swap! *app-state update :resources-page/category-search
             (fn [searches]
               (reduce #(assoc %1 %2 "") searches all-filters))))
    (js/setTimeout
     (fn []
       (when-let [el (.getElementById js/document
                                      (if was-collapsed
                                        (str "resources-filter-" (name filter-key))
                                        "resources-filter-search"))]
         (.focus el)))
     0)))

(defn toggle-resources-goal-filter [id]
  (swap! *app-state update :resources-page/filter-goals
         #(if (contains? % id) (disj % id) (conj % id)))
  (fetch-resources-or-journals))

(defn clear-resources-goal-filter []
  (swap! *app-state assoc :resources-page/filter-goals #{})
  (fetch-resources-or-journals))

(defn has-resources-goal-filter? []
  (seq (:resources-page/filter-goals @*app-state)))

(defn set-resources-category-search [category-key search-term]
  (swap! *app-state assoc-in [:resources-page/category-search category-key] search-term))

(defn toggle-shared-filter [filter-type id]
  (if (= filter-type constants/CATEGORY-TYPE-GOAL)
    (case (:active-tab @*app-state)
      :resources (toggle-resources-goal-filter id)
      :meets (toggle-meets-goal-filter id)
      nil)
    (let [filter-key (case filter-type
                       constants/CATEGORY-TYPE-PERSON :shared/filter-people
                       constants/CATEGORY-TYPE-PLACE :shared/filter-places
                       constants/CATEGORY-TYPE-PROJECT :shared/filter-projects)]
      (swap! *app-state update filter-key
             #(if (contains? % id)
                (disj % id)
                (conj % id)))
      (case (:active-tab @*app-state)
        :tasks (if (:tasks-page/recurring-mode @*app-state)
                 (fetch-recurring-tasks)
                 (fetch-tasks))
        :resources (fetch-resources-or-journals)
        :meets (if (:meets-page/series-mode @*app-state)
                 (fetch-meeting-series)
                 (fetch-meets))
        nil))))

(defn clear-shared-filter [filter-type]
  (let [filter-key (case filter-type
                     constants/CATEGORY-TYPE-PERSON :shared/filter-people
                     constants/CATEGORY-TYPE-PLACE :shared/filter-places
                     constants/CATEGORY-TYPE-PROJECT :shared/filter-projects)]
    (swap! *app-state assoc filter-key #{})
    (case (:active-tab @*app-state)
      :tasks (if (:tasks-page/recurring-mode @*app-state)
               (fetch-recurring-tasks)
               (fetch-tasks))
      :resources (fetch-resources-or-journals)
      :meets (if (:meets-page/series-mode @*app-state)
               (fetch-meeting-series)
               (fetch-meets))
      nil)))

(defn clear-uncollapsed-resource-filters []
  (let [collapsed (:resources-page/collapsed-filters @*app-state)
        all-filters #{:people :places :projects :goals}
        uncollapsed (clojure.set/difference all-filters collapsed)]
    (if (empty? uncollapsed)
      (swap! *app-state assoc
             :shared/filter-people #{}
             :shared/filter-places #{}
             :shared/filter-projects #{}
             :resources-page/filter-goals #{}
             :resources-page/category-search {:people "" :places "" :projects "" :goals ""})
      (do
        (doseq [filter-key uncollapsed]
          (case filter-key
            :people (swap! *app-state assoc :shared/filter-people #{})
            :places (swap! *app-state assoc :shared/filter-places #{})
            :projects (swap! *app-state assoc :shared/filter-projects #{})
            :goals (swap! *app-state assoc :resources-page/filter-goals #{})))
        (swap! *app-state assoc
               :resources-page/collapsed-filters all-filters
               :resources-page/category-search {:people "" :places "" :projects "" :goals ""})))
    (resources-state/set-importance-filter fetch-resources nil)
    (fetch-resources)))

(defn fetch-users []
  (users/fetch-users *app-state auth-headers))

(defn fetch-available-users []
  (users/fetch-available-users *app-state))

(defn add-user [username password on-success]
  (users/add-user *app-state auth-headers username password on-success))

(defn set-confirm-delete-user [user]
  (users/set-confirm-delete-user *app-state user))

(defn clear-confirm-delete-user []
  (users/clear-confirm-delete-user *app-state))

(defn delete-user [user-id]
  (users/delete-user *app-state auth-headers user-id))

(defn toggle-user-switcher []
  (users/toggle-user-switcher *app-state))

(defn close-user-switcher []
  (users/close-user-switcher *app-state))

(defn switch-user [user]
  (users/switch-user *app-state initial-collection-state fetch-all user))

(defn fetch-people []
  (categories/fetch-people *app-state auth-headers))

(defn fetch-places []
  (categories/fetch-places *app-state auth-headers))

(defn fetch-projects []
  (categories/fetch-projects *app-state auth-headers))

(defn fetch-goals []
  (categories/fetch-goals *app-state auth-headers))

(defn add-person [name on-success]
  (categories/add-person *app-state auth-headers name on-success))

(defn add-place [name on-success]
  (categories/add-place *app-state auth-headers name on-success))

(defn add-project [name on-success]
  (categories/add-project *app-state auth-headers name on-success))

(defn add-goal [name on-success]
  (categories/add-goal *app-state auth-headers name on-success))

(defn update-person [id name description tags badge-title on-success]
  (categories/update-person *app-state auth-headers fetch-tasks id name description tags badge-title on-success))

(defn update-place [id name description tags badge-title on-success]
  (categories/update-place *app-state auth-headers fetch-tasks id name description tags badge-title on-success))

(defn update-project [id name description tags badge-title on-success]
  (categories/update-project *app-state auth-headers fetch-tasks id name description tags badge-title on-success))

(defn update-goal [id name description tags badge-title on-success]
  (categories/update-goal *app-state auth-headers fetch-tasks id name description tags badge-title on-success))

(defn set-confirm-delete-category [category-type category]
  (categories/set-confirm-delete-category *app-state category-type category))

(defn clear-confirm-delete-category []
  (categories/clear-confirm-delete-category *app-state))

(defn delete-person [id]
  (categories/delete-person *app-state auth-headers fetch-tasks id))

(defn delete-place [id]
  (categories/delete-place *app-state auth-headers fetch-tasks id))

(defn delete-project [id]
  (categories/delete-project *app-state auth-headers fetch-tasks id))

(defn delete-goal [id]
  (categories/delete-goal *app-state auth-headers fetch-tasks id))

(defn set-editing-category [category-type id]
  (categories/set-editing-category *app-state category-type id))

(defn clear-editing-category []
  (categories/clear-editing-category *app-state))

(defn set-drag-category [category-type category-id]
  (categories/set-drag-category *app-state category-type category-id))

(defn set-drag-over-category [category-type category-id]
  (categories/set-drag-over-category *app-state category-type category-id))

(defn clear-category-drag-state []
  (categories/clear-category-drag-state *app-state))

(defn reorder-category [category-type category-id target-category-id position]
  (categories/reorder-category *app-state auth-headers
                               fetch-people fetch-places fetch-projects fetch-goals
                               category-type category-id target-category-id position))

(defn- fetch-opts-for-current-tab []
  (case (:active-tab @*app-state)
    :tasks (cond-> {:search-term (:tasks-page/filter-search @*app-state)
                     :importance (:tasks-page/importance-filter @*app-state)
                     :context (:work-private-mode @*app-state)
                     :strict (:strict-mode @*app-state)
                     :filter-people (:shared/filter-people @*app-state)
                     :filter-places (:shared/filter-places @*app-state)
                     :filter-projects (:shared/filter-projects @*app-state)
                     :filter-goals (:tasks-page/filter-goals @*app-state)}
             (:tasks-page/filter-recurring @*app-state)
             (assoc :recurring-task-id (:id (:tasks-page/filter-recurring @*app-state))))
    :today {:context (:work-private-mode @*app-state)
            :strict (:strict-mode @*app-state)
            :excluded-places (:today-page/excluded-places @*app-state)
            :excluded-projects (:today-page/excluded-projects @*app-state)}
    {:context (:work-private-mode @*app-state)
     :strict (:strict-mode @*app-state)}))

(defn fetch-tasks
  ([] (fetch-tasks (fetch-opts-for-current-tab)))
  ([opts]
   (tasks/fetch-tasks *app-state auth-headers today-page/calculate-best-horizon opts)))

(defn- today-fetch-opts []
  (today-page/current-fetch-opts *app-state))

(defn fetch-today-meets
  ([] (fetch-today-meets (today-fetch-opts)))
  ([opts]
   (meets-state/fetch-today-meets *app-state auth-headers today-page/calculate-best-horizon opts)))

(defn- fetch-today-all [opts]
  (fetch-tasks opts)
  (fetch-today-meets opts))

(declare add-task-with-categories)
(declare add-resource-with-categories)
(declare add-meet-with-categories)
(declare has-active-filters?)

(defn add-task-with-categories [title categories on-success]
  (tasks/add-task-with-categories *app-state auth-headers fetch-tasks current-scope title categories on-success))

(defn add-resource-with-categories [title link categories on-success]
  (resources-state/add-resource-with-categories *app-state auth-headers fetch-resources current-scope title link categories on-success))

(defn add-meet-with-categories [title categories on-success]
  (meets-state/add-meet-with-categories *app-state auth-headers fetch-meets current-scope title categories on-success))

(defn add-task [title on-success]
  (tasks/add-task *app-state auth-headers current-scope has-active-filters? #(set-pending-new-item :task %1 %2) title on-success))

(defn update-task [task-id title description tags on-success]
  (tasks/update-task *app-state auth-headers task-id title description tags on-success))

(defn categorize-task [task-id category-type category-id]
  (tasks/categorize-task *app-state auth-headers fetch-tasks task-id category-type category-id))

(defn uncategorize-task [task-id category-type category-id]
  (tasks/uncategorize-task *app-state auth-headers fetch-tasks task-id category-type category-id))

(defn set-task-due-date [task-id due-date]
  (tasks/set-task-due-date *app-state auth-headers task-id due-date))

(defn set-task-due-time [task-id due-time]
  (tasks/set-task-due-time *app-state auth-headers task-id due-time))

(defn set-confirm-delete-task [task]
  (tasks/set-confirm-delete-task *app-state task))

(defn set-task-dropdown-open [task-id]
  (tasks/set-task-dropdown-open *app-state task-id))

(defn clear-confirm-delete []
  (tasks/clear-confirm-delete *app-state))

(defn delete-task [task-id]
  (tasks/delete-task *app-state auth-headers task-id))

(defn set-task-done [task-id done?]
  (tasks/set-task-done *app-state auth-headers fetch-tasks task-id done?))

(defn set-confirm-undone-task [task]
  (swap! *app-state assoc :confirm-undone-task task))

(defn clear-confirm-undone []
  (swap! *app-state assoc :confirm-undone-task nil))

(defn confirm-undone-task [task-id]
  (clear-confirm-undone)
  (set-task-done task-id false))

(defn set-task-scope [task-id scope]
  (tasks/set-task-scope *app-state auth-headers task-id scope))

(defn set-task-importance [task-id importance]
  (tasks/set-task-importance *app-state auth-headers task-id importance))

(defn set-task-urgency [task-id urgency]
  (tasks/set-task-urgency *app-state auth-headers task-id urgency))

(defn set-task-today [task-id today?]
  (tasks/set-task-today *app-state auth-headers fetch-tasks task-id today?))

(defn set-task-lined-up-for [task-id date]
  (tasks/set-task-lined-up-for *app-state auth-headers fetch-tasks task-id date))

(defn set-task-reminder [task-id reminder-date]
  (tasks/set-task-reminder *app-state auth-headers task-id reminder-date))

(defn acknowledge-task-reminder [task-id]
  (tasks/acknowledge-task-reminder *app-state auth-headers task-id))

(defn open-reminder-modal [task]
  (swap! *app-state assoc :reminder-modal task))

(defn close-reminder-modal []
  (swap! *app-state dissoc :reminder-modal))

(defn add-task-to-today [title on-success]
  (tasks/add-task *app-state auth-headers current-scope (constantly false) nil title
                  (fn []
                    (let [task (first (:tasks @*app-state))]
                      (when task
                        (set-task-today (:id task) true)))
                    (when on-success (on-success)))))

(defn add-task-lined-up-for [title date on-success]
  (tasks/add-task *app-state auth-headers current-scope (constantly false) nil title
                  (fn []
                    (let [task (first (:tasks @*app-state))]
                      (when task
                        (set-task-lined-up-for (:id task) date)))
                    (when on-success (on-success)))))

(defn set-drag-task [task-id]
  (tasks/set-drag-task *app-state task-id))

(defn set-drag-over-task [task-id]
  (tasks/set-drag-over-task *app-state task-id))

(defn set-drag-over-urgency-section [section]
  (tasks/set-drag-over-urgency-section *app-state section))

(defn clear-drag-state []
  (tasks/clear-drag-state *app-state))

(defn reorder-task [task-id target-task-id position]
  (tasks/reorder-task *app-state auth-headers fetch-tasks task-id target-task-id position))

(defn set-sort-mode [mode]
  (tasks/set-sort-mode *app-state fetch-tasks mode))

(defn toggle-reminder-mode []
  (tasks/toggle-reminder-mode *app-state fetch-tasks))

(defn task-done? [task]
  (tasks/task-done? task))

(defn has-active-filters? []
  (tasks-page/has-active-filters? *app-state))

(defn has-active-shared-filters? []
  (tasks-page/has-active-shared-filters? *app-state))

(defn has-filter-for-type? [filter-type]
  (if (= filter-type constants/CATEGORY-TYPE-GOAL)
    (case (:active-tab @*app-state)
      :tasks (seq (:tasks-page/filter-goals @*app-state))
      :resources (seq (:resources-page/filter-goals @*app-state))
      :meets (seq (:meets-page/filter-goals @*app-state))
      nil)
    (tasks-page/has-filter-for-type? *app-state filter-type)))

(defn toggle-filter [filter-type id]
  (tasks-page/toggle-filter *app-state fetch-tasks filter-type id))

(defn clear-filter [filter-type]
  (tasks-page/clear-filter *app-state fetch-tasks filter-type))

(defn clear-filter-people []
  (tasks-page/clear-filter-people *app-state fetch-tasks))

(defn clear-filter-places []
  (tasks-page/clear-filter-places *app-state fetch-tasks))

(defn clear-filter-projects []
  (tasks-page/clear-filter-projects *app-state fetch-tasks))

(defn clear-filter-goals []
  (tasks-page/clear-filter-goals *app-state fetch-tasks))

(defn set-importance-filter [level]
  (tasks-page/set-importance-filter *app-state fetch-tasks level))

(defn clear-importance-filter []
  (tasks-page/clear-importance-filter *app-state fetch-tasks))

(defn clear-uncollapsed-task-filters []
  (tasks-page/clear-uncollapsed-task-filters *app-state
    (if (:tasks-page/recurring-mode @*app-state)
      fetch-recurring-tasks
      fetch-tasks)))

(defn toggle-filter-collapsed [filter-key]
  (tasks-page/toggle-filter-collapsed *app-state filter-key))

(defn set-filter-search [search-term]
  (tasks-page/set-filter-search *app-state fetch-tasks search-term))

(defn set-category-search [category-key search-term]
  (tasks-page/set-category-search *app-state category-key search-term))

(defn open-category-selector [selector-id]
  (tasks-page/open-category-selector *app-state selector-id))

(defn close-category-selector []
  (tasks-page/close-category-selector *app-state))

(defn set-category-selector-search [search-term]
  (tasks-page/set-category-selector-search *app-state search-term))

(defn focus-tasks-search []
  (tasks-page/focus-tasks-search))

(defn filtered-tasks []
  (tasks-page/filtered-tasks *app-state))

(defn set-pending-new-item [type title on-success & [extra]]
  (tasks-page/set-pending-new-item *app-state type title on-success extra))

(defn clear-pending-new-item []
  (tasks-page/clear-pending-new-item *app-state))

(defn update-pending-category [category-type id]
  (tasks-page/update-pending-category *app-state category-type id))

(defn confirm-pending-new-item []
  (tasks-page/confirm-pending-new-item *app-state
    {:task add-task-with-categories
     :resource add-resource-with-categories
     :meet add-meet-with-categories
     :meeting-series add-meeting-series-with-categories
     :recurring-task add-recurring-task-with-categories}))

(defn set-upcoming-horizon [horizon]
  (today-page/set-upcoming-horizon *app-state horizon))

(defn toggle-today-excluded-place [place-id]
  (today-page/toggle-today-excluded-place *app-state fetch-today-all place-id))

(defn toggle-today-excluded-project [project-id]
  (today-page/toggle-today-excluded-project *app-state fetch-today-all project-id))

(defn clear-today-excluded-places []
  (today-page/clear-today-excluded-places *app-state fetch-today-all))

(defn clear-today-excluded-projects []
  (today-page/clear-today-excluded-projects *app-state fetch-today-all))

(defn clear-uncollapsed-today-filters []
  (today-page/clear-uncollapsed-today-filters *app-state fetch-today-all))

(defn toggle-today-filter-collapsed [filter-key]
  (today-page/toggle-today-filter-collapsed *app-state filter-key))

(defn set-today-category-search [category-key search-term]
  (today-page/set-today-category-search *app-state category-key search-term))

(defn set-today-selected-view [view]
  (today-page/set-today-selected-view *app-state view))

(defn set-selected-day [day-offset]
  (today-page/set-selected-day *app-state day-offset))

(defn selected-day-date []
  (today-page/selected-day-date *app-state))

(defn selected-day-tasks []
  (today-page/selected-day-tasks *app-state))

(defn selected-day-meets []
  (today-page/selected-day-meets *app-state))

(defn overdue-tasks []
  (today-page/overdue-tasks *app-state))

(defn today-tasks []
  (today-page/today-tasks *app-state))

(defn today-flagged-tasks []
  (today-page/today-flagged-tasks *app-state))

(defn upcoming-tasks []
  (today-page/upcoming-tasks *app-state))

(defn reminder-tasks []
  (today-page/reminder-tasks *app-state))

(defn superurgent-tasks []
  (today-page/superurgent-tasks *app-state))

(defn urgent-tasks []
  (today-page/urgent-tasks *app-state))

(defn today-meets []
  (today-page/today-meets *app-state))

(defn upcoming-meets []
  (today-page/upcoming-meets *app-state))

(defn- fetch-meets-or-series []
  (if (:meets-page/series-mode @*app-state)
    (fetch-meeting-series)
    (fetch-meets)))

(defn- fetch-resources-or-journals []
  (if (:resources-page/journals-mode @*app-state)
    (fetch-journals)
    (if (:resources-page/filter-journal @*app-state)
      (fetch-journal-entries)
      (fetch-resources))))

(def tab-initializers
  (ui/make-tab-initializers *app-state {:fetch-tasks fetch-tasks
                                        :fetch-today-meets fetch-today-meets
                                        :fetch-today-journal-entries fetch-today-journal-entries
                                        :fetch-messages fetch-messages
                                        :fetch-resources fetch-resources-or-journals
                                        :fetch-meets fetch-meets-or-series
                                        :is-admin is-admin?
                                        :has-mail has-mail?}))

(defn set-active-tab [tab]
  (ui/set-active-tab *app-state tab-initializers tab))

(defn toggle-expanded [page-key task-id]
  (ui/toggle-expanded *app-state page-key task-id))

(defn set-editing [task-id]
  (ui/set-editing *app-state task-id))

(defn clear-editing []
  (ui/clear-editing *app-state))

(defn open-preview-modal [entity-type entity]
  (let [modal {:type entity-type :entity entity :tab :preview}]
    (swap! *app-state assoc :editing-modal modal)
    (when-let [path (url/entity->path modal)]
      (url/push-state! path))))

(defn open-relation-in-modal [relation-type relation-id]
  (let [entity-type (url/prefix->type relation-type)
        api-path (str (url/prefix->api-path relation-type) relation-id)]
    (api/fetch-json api-path (auth-headers)
      (fn [entity]
        (open-preview-modal entity-type entity)))))

(defn set-editing-modal [entity-type entity]
  (let [modal {:type entity-type :entity entity}]
    (swap! *app-state assoc :editing-modal modal)
    (when-let [path (url/entity->path modal)]
      (url/push-state! (str path "?section=edit")))))

(defn clear-editing-modal []
  (swap! *app-state assoc :editing-modal nil)
  (url/push-state! "/"))

(defn set-work-private-mode [mode]
  (ui/set-work-private-mode *app-state fetch-tasks fetch-today-meets fetch-resources-or-journals fetch-meets-or-series fetch-messages fetch-today-journal-entries mode))

(defn toggle-strict-mode []
  (ui/toggle-strict-mode *app-state fetch-tasks fetch-today-meets fetch-resources-or-journals fetch-meets-or-series fetch-messages fetch-today-journal-entries))

(defn toggle-dark-mode []
  (ui/toggle-dark-mode *app-state))

(ui/setup-dark-mode-watcher *app-state)

(defn export-data []
  (ui/export-data auth-headers *app-state))

(defn relation-mode-active? []
  (relations-state/relation-mode-active?))

(defn relation-source []
  (relations-state/relation-source))

(defn toggle-relation-mode []
  (relations-state/toggle-relation-mode))

(defn abort-relation-mode []
  (relations-state/abort-relation-mode))

(defn- refetch-for-active-tab []
  (case (:active-tab @*app-state)
    :tasks (fetch-tasks)
    :resources (fetch-resources-or-journals)
    :meets (if (:meets-page/series-mode @*app-state)
             (fetch-meeting-series)
             (fetch-meets))
    :today (do (fetch-tasks) (fetch-today-meets) (fetch-today-journal-entries))
    nil))

(defn item-type->prefix [item-type]
  (relations-state/item-type->prefix item-type))

(defn select-for-relation [item-type item-id]
  (let [prefix (relations-state/item-type->prefix item-type)
        source (relations-state/relation-source)]
    (if source
      (relations-state/add-relation auth-headers
                                    (:type source) (:id source)
                                    prefix item-id
                                    refetch-for-active-tab)
      (relations-state/set-relation-source prefix item-id))))

(defn delete-relation [source-type source-id target-type target-id]
  (relations-state/delete-relation auth-headers
                                   source-type source-id
                                   target-type target-id
                                   refetch-for-active-tab))

(defn open-create-date-modal [entity-type entity]
  (swap! *app-state assoc :create-date-modal {:type entity-type :entity entity :taken-dates nil :loading? true})
  (let [url (case entity-type
              :recurring-task (str "/api/recurring-tasks/" (:id entity) "/taken-dates")
              :meeting-series (str "/api/meeting-series/" (:id entity) "/taken-dates"))]
    (api/fetch-json url (auth-headers)
      (fn [result]
        (swap! *app-state update :create-date-modal assoc :taken-dates (set (:dates result)) :loading? false)))))

(defn close-create-date-modal []
  (swap! *app-state dissoc :create-date-modal))

(defn create-date-modal-state []
  (:create-date-modal @*app-state))

(defn confirm-create-date-modal [date]
  (when-let [{:keys [type entity]} (:create-date-modal @*app-state)]
    (let [js-d (js/Date. (str date "T00:00:00"))
          day-num (scheduling/js-day-to-iso-day (.getDay js-d))
          time (scheduling/get-schedule-time-for-day (:schedule_time entity) day-num)]
      (case type
        :recurring-task
        (recurring-tasks-state/create-task-for-recurring *app-state auth-headers fetch-recurring-tasks (:id entity) date time)
        :meeting-series
        (meeting-series-state/create-meeting-for-series *app-state auth-headers
          (fn [] (fetch-meeting-series) (fetch-today-meets))
          (:id entity) date time)))
    (swap! *app-state dissoc :create-date-modal)))
