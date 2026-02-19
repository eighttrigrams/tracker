(ns et.tr.ui.state
  (:require [clojure.set]
            [reagent.core :as r]
            [et.tr.ui.constants :as constants]
            [et.tr.ui.state.auth :as auth]
            [et.tr.ui.state.mail :as mail]
            [et.tr.ui.state.users :as users]
            [et.tr.ui.state.categories :as categories]
            [et.tr.ui.state.tasks :as tasks]
            [et.tr.ui.state.tasks-page :as tasks-page]
            [et.tr.ui.state.today-page :as today-page]
            [et.tr.ui.state.resources :as resources-state]
            [et.tr.ui.state.meets :as meets-state]
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
   :today-meets []
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
                            :confirm-delete-task nil

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
                            :upcoming-horizon nil

                            ;; Resources page state
                            :resources-page/collapsed-filters #{:people :places :projects}
                            :resources-page/category-search {:people "" :places "" :projects ""}

                            ;; Meets page state
                            :meets-page/collapsed-filters #{:people :places :projects}
                            :meets-page/category-search {:people "" :places "" :projects ""}

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
(declare fetch-today-meets)
(declare fetch-messages)
(declare fetch-resources)
(declare fetch-meets)
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

(defn clear-all-mail-filters []
  (mail/clear-all-mail-filters *app-state auth-headers))

(defn set-editing-message [id]
  (mail/set-editing-message id))

(defn clear-editing-message []
  (mail/clear-editing-message))

(defn update-message-annotation [message-id annotation]
  (mail/update-message-annotation *app-state auth-headers message-id annotation))

(defn add-message [title on-success]
  (mail/add-message *app-state auth-headers title on-success))

(defn set-message-dropdown-open [message-id]
  (mail/set-message-dropdown-open message-id))

(defn convert-message-to-resource [message-id link]
  (mail/convert-message-to-resource *app-state auth-headers message-id link))

(declare has-active-shared-filters?)
(declare set-pending-new-item)

(defn- resources-fetch-opts []
  {:search-term (:filter-search @resources-state/*resources-page-state)
   :importance (:importance-filter @resources-state/*resources-page-state)
   :context (:work-private-mode @*app-state)
   :strict (:strict-mode @*app-state)
   :filter-people (:shared/filter-people @*app-state)
   :filter-places (:shared/filter-places @*app-state)
   :filter-projects (:shared/filter-projects @*app-state)})

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

(defn set-resource-importance-filter [level]
  (resources-state/set-importance-filter fetch-resources level))

(defn clear-all-resource-filters []
  (resources-state/clear-all-resource-filters fetch-resources))

(defn categorize-resource [resource-id category-type category-id]
  (resources-state/categorize-resource *app-state auth-headers fetch-resources resource-id category-type category-id))

(defn uncategorize-resource [resource-id category-type category-id]
  (resources-state/uncategorize-resource *app-state auth-headers fetch-resources resource-id category-type category-id))

(defn- meets-fetch-opts []
  {:search-term (:filter-search @meets-state/*meets-page-state)
   :importance (:importance-filter @meets-state/*meets-page-state)
   :sort-mode (:sort-mode @meets-state/*meets-page-state)
   :context (:work-private-mode @*app-state)
   :strict (:strict-mode @*app-state)
   :filter-people (:shared/filter-people @*app-state)
   :filter-places (:shared/filter-places @*app-state)
   :filter-projects (:shared/filter-projects @*app-state)})

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

(defn toggle-meets-filter-collapsed [filter-key]
  (let [was-collapsed (contains? (:meets-page/collapsed-filters @*app-state) filter-key)
        all-filters #{:people :places :projects}]
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

(defn set-meets-category-search [category-key search-term]
  (swap! *app-state assoc-in [:meets-page/category-search category-key] search-term))

(defn clear-uncollapsed-meet-filters []
  (let [collapsed (:meets-page/collapsed-filters @*app-state)
        all-filters #{:people :places :projects}
        uncollapsed (clojure.set/difference all-filters collapsed)]
    (if (empty? uncollapsed)
      (swap! *app-state assoc
             :shared/filter-people #{}
             :shared/filter-places #{}
             :shared/filter-projects #{}
             :meets-page/category-search {:people "" :places "" :projects ""})
      (do
        (doseq [filter-key uncollapsed]
          (case filter-key
            :people (swap! *app-state assoc :shared/filter-people #{})
            :places (swap! *app-state assoc :shared/filter-places #{})
            :projects (swap! *app-state assoc :shared/filter-projects #{})))
        (swap! *app-state assoc
               :meets-page/collapsed-filters all-filters
               :meets-page/category-search {:people "" :places "" :projects ""})))
    (meets-state/set-importance-filter fetch-meets nil)
    (fetch-meets)))

(defn toggle-resources-filter-collapsed [filter-key]
  (let [was-collapsed (contains? (:resources-page/collapsed-filters @*app-state) filter-key)
        all-filters #{:people :places :projects}]
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

(defn set-resources-category-search [category-key search-term]
  (swap! *app-state assoc-in [:resources-page/category-search category-key] search-term))

(defn toggle-shared-filter [filter-type id]
  (let [filter-key (case filter-type
                     constants/CATEGORY-TYPE-PERSON :shared/filter-people
                     constants/CATEGORY-TYPE-PLACE :shared/filter-places
                     constants/CATEGORY-TYPE-PROJECT :shared/filter-projects)]
    (swap! *app-state update filter-key
           #(if (contains? % id)
              (disj % id)
              (conj % id)))
    (case (:active-tab @*app-state)
      :tasks (fetch-tasks)
      :resources (fetch-resources)
      :meets (fetch-meets)
      nil)))

(defn clear-shared-filter [filter-type]
  (let [filter-key (case filter-type
                     constants/CATEGORY-TYPE-PERSON :shared/filter-people
                     constants/CATEGORY-TYPE-PLACE :shared/filter-places
                     constants/CATEGORY-TYPE-PROJECT :shared/filter-projects)]
    (swap! *app-state assoc filter-key #{})
    (case (:active-tab @*app-state)
      :tasks (fetch-tasks)
      :resources (fetch-resources)
      :meets (fetch-meets)
      nil)))

(defn clear-uncollapsed-resource-filters []
  (let [collapsed (:resources-page/collapsed-filters @*app-state)
        all-filters #{:people :places :projects}
        uncollapsed (clojure.set/difference all-filters collapsed)]
    (if (empty? uncollapsed)
      (swap! *app-state assoc
             :shared/filter-people #{}
             :shared/filter-places #{}
             :shared/filter-projects #{}
             :resources-page/category-search {:people "" :places "" :projects ""})
      (do
        (doseq [filter-key uncollapsed]
          (case filter-key
            :people (swap! *app-state assoc :shared/filter-people #{})
            :places (swap! *app-state assoc :shared/filter-places #{})
            :projects (swap! *app-state assoc :shared/filter-projects #{})))
        (swap! *app-state assoc
               :resources-page/collapsed-filters all-filters
               :resources-page/category-search {:people "" :places "" :projects ""})))
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
    :tasks {:search-term (:tasks-page/filter-search @*app-state)
            :importance (:tasks-page/importance-filter @*app-state)
            :context (:work-private-mode @*app-state)
            :strict (:strict-mode @*app-state)
            :filter-people (:shared/filter-people @*app-state)
            :filter-places (:shared/filter-places @*app-state)
            :filter-projects (:shared/filter-projects @*app-state)
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

(defn has-active-shared-filters? []
  (tasks-page/has-active-shared-filters? *app-state))

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
     :meet add-meet-with-categories}))

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

(defn today-meets []
  (today-page/today-meets *app-state))

(defn upcoming-meets []
  (today-page/upcoming-meets *app-state))

(def tab-initializers
  (ui/make-tab-initializers *app-state {:fetch-tasks fetch-tasks
                                        :fetch-today-meets fetch-today-meets
                                        :fetch-messages fetch-messages
                                        :fetch-resources fetch-resources
                                        :fetch-meets fetch-meets
                                        :is-admin is-admin?}))

(defn set-active-tab [tab]
  (ui/set-active-tab *app-state tab-initializers tab))

(defn toggle-expanded [page-key task-id]
  (ui/toggle-expanded *app-state page-key task-id))

(defn set-editing [task-id]
  (ui/set-editing *app-state task-id))

(defn clear-editing []
  (ui/clear-editing *app-state))

(defn set-work-private-mode [mode]
  (ui/set-work-private-mode *app-state fetch-tasks fetch-today-meets fetch-resources fetch-meets mode))

(defn toggle-strict-mode []
  (ui/toggle-strict-mode *app-state fetch-tasks fetch-today-meets fetch-resources fetch-meets))

(defn toggle-dark-mode []
  (ui/toggle-dark-mode *app-state))

(ui/setup-dark-mode-watcher *app-state)

(defn export-data []
  (ui/export-data auth-headers *app-state))
