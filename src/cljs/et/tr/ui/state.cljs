(ns et.tr.ui.state
  (:require [reagent.core :as r]
            [et.tr.ui.constants :as constants]
            [et.tr.ui.state.auth :as auth]
            [et.tr.ui.state.mail :as mail]
            [et.tr.ui.state.users :as users]
            [et.tr.ui.state.categories :as categories]
            [et.tr.ui.state.tasks :as tasks]
            [et.tr.ui.state.tasks-page :as tasks-page]
            [et.tr.ui.state.today-page :as today-page]
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
   :upcoming-horizon nil})

(defonce *app-state (r/atom {;; Data collections
                            :tasks []
                            :people []
                            :places []
                            :projects []
                            :goals []
                            :messages []
                            :users []
                            :available-users []

                            ;; Tasks page state
                            :tasks-page/filter-people #{}
                            :tasks-page/filter-places #{}
                            :tasks-page/filter-projects #{}
                            :tasks-page/filter-goals #{}
                            :tasks-page/filter-search ""
                            :tasks-page/category-search {:people "" :places "" :projects "" :goals ""}
                            :tasks-page/importance-filter nil
                            :tasks-page/collapsed-filters #{:people :places :projects :goals}
                            :tasks-page/expanded-task nil
                            :editing-task nil
                            :pending-new-task nil
                            :confirm-delete-task nil

                            ;; Today page state
                            :today-page/excluded-places #{}
                            :today-page/excluded-projects #{}
                            :today-page/collapsed-filters #{:places :projects}
                            :today-page/category-search {:places "" :projects ""}
                            :today-page/expanded-task nil
                            :today-page/selected-view :urgent
                            :upcoming-horizon nil

                            ;; Task dropdown state
                            :task-dropdown-open nil

                            ;; Category selector state
                            :category-selector/open nil
                            :category-selector/search ""

                            ;; Global UI state
                            :active-tab :today
                            :sort-mode :today
                            :tasks-page/last-sort-mode :manual
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
  (let [current-user (:current-user @*app-state)]
    (or (nil? (:id current-user))
        (:is_admin current-user))))

(defn- current-scope []
  (name (:work-private-mode @*app-state)))

(declare fetch-tasks)
(declare fetch-messages)
(declare fetch-users)
(declare fetch-people)
(declare fetch-places)
(declare fetch-projects)
(declare fetch-goals)

(defn- fetch-all [user]
  (fetch-tasks)
  (fetch-people)
  (fetch-places)
  (fetch-projects)
  (fetch-goals)
  (when (:is_admin user)
    (fetch-messages)
    (fetch-users)))

(defn fetch-auth-required []
  (auth/fetch-auth-required *app-state auth-headers initial-collection-state fetch-all))

(defn login [username password on-success]
  (auth/login *app-state fetch-messages fetch-users username password on-success))

(defn logout []
  (auth/logout *app-state initial-collection-state))

(defn update-user-language [language]
  (auth/update-user-language *app-state auth-headers language))

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

(defn set-editing-message [id]
  (mail/set-editing-message id))

(defn clear-editing-message []
  (mail/clear-editing-message))

(defn update-message-annotation [message-id annotation]
  (mail/update-message-annotation *app-state auth-headers message-id annotation))

(defn add-message [title on-success]
  (mail/add-message *app-state auth-headers title on-success))

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

(defn update-person [id name description on-success]
  (categories/update-person *app-state auth-headers fetch-tasks id name description on-success))

(defn update-place [id name description on-success]
  (categories/update-place *app-state auth-headers fetch-tasks id name description on-success))

(defn update-project [id name description on-success]
  (categories/update-project *app-state auth-headers fetch-tasks id name description on-success))

(defn update-goal [id name description on-success]
  (categories/update-goal *app-state auth-headers fetch-tasks id name description on-success))

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
    :tasks {:search-term (:tasks-page/filter-search @*app-state)
            :importance (:tasks-page/importance-filter @*app-state)
            :context (:work-private-mode @*app-state)
            :strict (:strict-mode @*app-state)
            :filter-people (:tasks-page/filter-people @*app-state)
            :filter-places (:tasks-page/filter-places @*app-state)
            :filter-projects (:tasks-page/filter-projects @*app-state)
            :filter-goals (:tasks-page/filter-goals @*app-state)}
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

(declare add-task-with-categories)
(declare has-active-filters?)
(declare set-pending-new-task)

(defn add-task-with-categories [title categories on-success]
  (tasks/add-task-with-categories *app-state auth-headers fetch-tasks current-scope title categories on-success))

(defn add-task [title on-success]
  (tasks/add-task *app-state auth-headers current-scope has-active-filters? set-pending-new-task title on-success))

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

(defn set-task-scope [task-id scope]
  (tasks/set-task-scope *app-state auth-headers task-id scope))

(defn set-task-importance [task-id importance]
  (tasks/set-task-importance *app-state auth-headers task-id importance))

(defn set-task-urgency [task-id urgency]
  (tasks/set-task-urgency *app-state auth-headers task-id urgency))

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

(defn task-done? [task]
  (tasks/task-done? task))

(defn has-active-filters? []
  (tasks-page/has-active-filters? *app-state))

(defn has-filter-for-type? [filter-type]
  (tasks-page/has-filter-for-type? *app-state filter-type))

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
  (tasks-page/clear-uncollapsed-task-filters *app-state fetch-tasks))

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

(defn set-pending-new-task [title on-success]
  (tasks-page/set-pending-new-task *app-state title on-success))

(defn clear-pending-new-task []
  (tasks-page/clear-pending-new-task *app-state))

(defn update-pending-category [category-type id]
  (tasks-page/update-pending-category *app-state category-type id))

(defn confirm-pending-new-task []
  (tasks-page/confirm-pending-new-task *app-state add-task-with-categories))

(defn set-upcoming-horizon [horizon]
  (today-page/set-upcoming-horizon *app-state horizon))

(defn toggle-today-excluded-place [place-id]
  (today-page/toggle-today-excluded-place *app-state fetch-tasks place-id))

(defn toggle-today-excluded-project [project-id]
  (today-page/toggle-today-excluded-project *app-state fetch-tasks project-id))

(defn clear-today-excluded-places []
  (today-page/clear-today-excluded-places *app-state fetch-tasks))

(defn clear-today-excluded-projects []
  (today-page/clear-today-excluded-projects *app-state fetch-tasks))

(defn clear-uncollapsed-today-filters []
  (today-page/clear-uncollapsed-today-filters *app-state fetch-tasks))

(defn toggle-today-filter-collapsed [filter-key]
  (today-page/toggle-today-filter-collapsed *app-state filter-key))

(defn set-today-category-search [category-key search-term]
  (today-page/set-today-category-search *app-state category-key search-term))

(defn set-today-selected-view [view]
  (today-page/set-today-selected-view *app-state view))

(defn overdue-tasks []
  (today-page/overdue-tasks *app-state))

(defn today-tasks []
  (today-page/today-tasks *app-state))

(defn upcoming-tasks []
  (today-page/upcoming-tasks *app-state))

(defn superurgent-tasks []
  (today-page/superurgent-tasks *app-state))

(defn urgent-tasks []
  (today-page/urgent-tasks *app-state))

(def tab-initializers
  (ui/make-tab-initializers *app-state fetch-tasks fetch-messages is-admin?))

(defn set-active-tab [tab]
  (ui/set-active-tab *app-state tab-initializers tab))

(defn toggle-expanded [page-key task-id]
  (ui/toggle-expanded *app-state page-key task-id))

(defn set-editing [task-id]
  (ui/set-editing *app-state task-id))

(defn clear-editing []
  (ui/clear-editing *app-state))

(defn set-work-private-mode [mode]
  (ui/set-work-private-mode *app-state fetch-tasks mode))

(defn toggle-strict-mode []
  (ui/toggle-strict-mode *app-state fetch-tasks))

(defn toggle-dark-mode []
  (ui/toggle-dark-mode *app-state))

(ui/setup-dark-mode-watcher *app-state)

(defn export-data []
  (ui/export-data auth-headers *app-state))
