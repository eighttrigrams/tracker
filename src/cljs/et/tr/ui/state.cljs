(ns et.tr.ui.state
  (:require [reagent.core :as r]
            [ajax.core :refer [GET POST PUT DELETE]]
            [clojure.string :as str]
            [et.tr.filters :as filters]
            [et.tr.i18n :as i18n]
            [et.tr.ui.date :as date]
            [et.tr.ui.api :as api]))

(def ^:const CATEGORY-TYPE-PERSON "person")
(def ^:const CATEGORY-TYPE-PLACE "place")
(def ^:const CATEGORY-TYPE-PROJECT "project")
(def ^:const CATEGORY-TYPE-GOAL "goal")

;; State organization:
;; - Page-specific keys use namespace prefixes: :tasks-page/*, :today-page/*, etc.
;; - Global UI state uses namespace prefixes: :category-selector/*, :mail-page/*
;; - Top-level keys (:tasks, :people, etc.) are shared data collections

(def initial-collection-state
  {:tasks []
   :people []
   :places []
   :projects []
   :goals []
   :messages []
   :upcoming-horizon nil})

(defonce app-state (r/atom {;; Data collections
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

                            ;; Category selector state
                            :category-selector/open nil
                            :category-selector/search ""

                            ;; Mail page state
                            :mail-page/sort-mode :recent
                            :mail-page/expanded-message nil
                            :mail-page/fetch-request-id 0
                            :confirm-delete-message nil

                            ;; Global UI state
                            :active-tab :today
                            :sort-mode :manual
                            :drag-task nil
                            :drag-over-task nil
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
  (let [token (:token @app-state)
        current-user (:current-user @app-state)
        user-id (:id current-user)]
    (cond-> {}
      token (assoc "Authorization" (str "Bearer " token))
      (and (not token) current-user) (assoc "X-User-Id" (if (nil? user-id) "null" (str user-id))))))

(declare fetch-tasks)
(declare fetch-users)
(declare fetch-available-users)
(declare fetch-people)
(declare fetch-places)
(declare fetch-projects)
(declare fetch-goals)
(declare fetch-messages)
(declare calculate-best-horizon)
(declare recalculate-today-horizon)
(declare is-admin?)

(defn- fetch-collection [endpoint state-key]
  (GET endpoint
    {:response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler #(swap! app-state assoc state-key %)}))

(defn- save-auth-to-storage [token user]
  (when token
    (.setItem js/localStorage "auth-token" token))
  (when user
    (.setItem js/localStorage "auth-user" (js/JSON.stringify (clj->js user)))))

(defn- clear-auth-from-storage []
  (.removeItem js/localStorage "auth-token")
  (.removeItem js/localStorage "auth-user"))

(defn- load-auth-from-storage []
  (let [token (.getItem js/localStorage "auth-token")
        user-str (.getItem js/localStorage "auth-user")]
    {:token token
     :user (when user-str (js->clj (js/JSON.parse user-str) :keywordize-keys true))}))

(defn- apply-user-language [user]
  (let [lang (or (:language user) "en")]
    (i18n/set-language! lang)))

(defn fetch-auth-required []
  (GET "/api/auth/required"
    {:response-format :json
     :keywords? true
     :handler (fn [resp]
                (swap! app-state assoc :auth-required? (:required resp))
                (if-not (:required resp)
                  (let [admin-user {:id nil :username "admin" :is_admin true :language "en"}]
                    (swap! app-state assoc
                           :logged-in? true
                           :current-user admin-user)
                    (apply-user-language admin-user)
                    (fetch-tasks)
                    (fetch-people)
                    (fetch-places)
                    (fetch-projects)
                    (fetch-goals)
                    (fetch-messages)
                    (fetch-available-users)
                    (fetch-users))
                  (let [{:keys [token user]} (load-auth-from-storage)]
                    (when (and token user)
                      (swap! app-state assoc
                             :logged-in? true
                             :token token
                             :current-user user)
                      (apply-user-language user)
                      (fetch-tasks)
                      (fetch-people)
                      (fetch-places)
                      (fetch-projects)
                      (fetch-goals)
                      (when (:is_admin user)
                        (fetch-messages)
                        (fetch-users))))))}))

(defn login [username password on-success]
  (POST "/api/auth/login"
    {:params {:username username :password password}
     :format :json
     :response-format :json
     :keywords? true
     :handler (fn [resp]
                (let [user (:user resp)
                      token (:token resp)]
                  (swap! app-state assoc
                         :logged-in? true
                         :token token
                         :current-user user
                         :error nil)
                  (save-auth-to-storage token user)
                  (apply-user-language user)
                  (when on-success (on-success))
                  (when (:is_admin user)
                    (fetch-messages)
                    (fetch-users))))
     :error-handler (fn [resp]
                      (swap! app-state assoc :error (get-in resp [:response :error] "Invalid credentials")))}))

(defn logout []
  (clear-auth-from-storage)
  (swap! app-state merge
         initial-collection-state
         {:logged-in? false
          :token nil
          :current-user nil
          :users []}))

(defn clear-error []
  (swap! app-state assoc :error nil))

(defn fetch-tasks []
  (let [sort-mode (name (:sort-mode @app-state))]
    (GET (str "/api/tasks?sort=" sort-mode)
      {:response-format :json
       :keywords? true
       :headers (auth-headers)
       :handler (fn [tasks]
                  (swap! app-state assoc :tasks tasks)
                  (when (nil? (:upcoming-horizon @app-state))
                    (swap! app-state assoc :upcoming-horizon (calculate-best-horizon tasks))))})))

(defn has-active-filters? []
  (let [filter-people (:tasks-page/filter-people @app-state)
        filter-places (:tasks-page/filter-places @app-state)
        filter-projects (:tasks-page/filter-projects @app-state)
        filter-goals (:tasks-page/filter-goals @app-state)]
    (or (seq filter-people) (seq filter-places) (seq filter-projects) (seq filter-goals))))

(defn- current-scope []
  (name (:work-private-mode @app-state)))

(defn- categorize-task-batch [task-id category-type ids]
  (doseq [id ids]
    (api/post-json (str "/api/tasks/" task-id "/categorize")
      {:category-type category-type :category-id id}
      (auth-headers)
      (fn [_]))))

(defn add-task-with-categories [title categories on-success]
  (POST "/api/tasks"
    {:params {:title title :scope (current-scope)}
     :format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [task]
                (let [task-id (:id task)
                      {:keys [people places projects goals]} categories]
                  (categorize-task-batch task-id CATEGORY-TYPE-PERSON people)
                  (categorize-task-batch task-id CATEGORY-TYPE-PLACE places)
                  (categorize-task-batch task-id CATEGORY-TYPE-PROJECT projects)
                  (categorize-task-batch task-id CATEGORY-TYPE-GOAL goals)
                  (js/setTimeout fetch-tasks 500)
                  (swap! app-state update :tasks #(cons task %))
                  (when on-success (on-success))))
     :error-handler (fn [resp]
                      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to add task")))}))

(defn set-pending-new-task [title on-success]
  (let [filter-people (:tasks-page/filter-people @app-state)
        filter-places (:tasks-page/filter-places @app-state)
        filter-projects (:tasks-page/filter-projects @app-state)
        filter-goals (:tasks-page/filter-goals @app-state)]
    (swap! app-state assoc :pending-new-task
           {:title title
            :on-success on-success
            :categories {:people filter-people
                         :places filter-places
                         :projects filter-projects
                         :goals filter-goals}})))

(defn clear-pending-new-task []
  (swap! app-state assoc :pending-new-task nil))

(defn update-pending-category [category-type id]
  (let [key (case category-type
              CATEGORY-TYPE-PERSON :people
              CATEGORY-TYPE-PLACE :places
              CATEGORY-TYPE-PROJECT :projects
              CATEGORY-TYPE-GOAL :goals
              (keyword category-type))]
    (swap! app-state update-in [:pending-new-task :categories key]
           #(if (contains? % id) (disj % id) (conj (or % #{}) id)))))

(defn confirm-pending-new-task []
  (when-let [{:keys [title on-success categories]} (:pending-new-task @app-state)]
    (add-task-with-categories title categories on-success)
    (clear-pending-new-task)))

(defn add-task [title on-success]
  (if (str/blank? title)
    (swap! app-state assoc :error "Title is required")
    (if (has-active-filters?)
      (set-pending-new-task title on-success)
      (POST "/api/tasks"
        {:params {:title title :scope (current-scope)}
         :format :json
         :response-format :json
         :keywords? true
         :headers (auth-headers)
         :handler (fn [task]
                    (swap! app-state update :tasks #(cons task %))
                    (when on-success (on-success)))
         :error-handler (fn [resp]
                          (swap! app-state assoc :error (get-in resp [:response :error] "Failed to add task")))}))))

(defn fetch-people []
  (fetch-collection "/api/people" :people))

(defn fetch-places []
  (fetch-collection "/api/places" :places))

(defn add-person [name on-success]
  (api/post-json "/api/people" {:name name} (auth-headers)
    (fn [person]
      (swap! app-state update :people conj person)
      (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to add person")))))

(defn add-place [name on-success]
  (api/post-json "/api/places" {:name name} (auth-headers)
    (fn [place]
      (swap! app-state update :places conj place)
      (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to add place")))))

(defn fetch-projects []
  (fetch-collection "/api/projects" :projects))

(defn fetch-goals []
  (fetch-collection "/api/goals" :goals))

(defn add-project [name on-success]
  (api/post-json "/api/projects" {:name name} (auth-headers)
    (fn [project]
      (swap! app-state update :projects conj project)
      (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to add project")))))

(defn add-goal [name on-success]
  (api/post-json "/api/goals" {:name name} (auth-headers)
    (fn [goal]
      (swap! app-state update :goals conj goal)
      (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to add goal")))))

(defn categorize-task [task-id category-type category-id]
  (api/post-json (str "/api/tasks/" task-id "/categorize")
    {:category-type category-type :category-id category-id}
    (auth-headers)
    (fn [_] (fetch-tasks))))

(defn uncategorize-task [task-id category-type category-id]
  (api/delete-json (str "/api/tasks/" task-id "/categorize")
    {:category-type category-type :category-id category-id}
    (auth-headers)
    (fn [_] (fetch-tasks))))

(defn update-task [task-id title description on-success]
  (api/put-json (str "/api/tasks/" task-id)
    {:title title :description description}
    (auth-headers)
    (fn [updated-task]
      (swap! app-state update :tasks
             (fn [tasks]
               (mapv #(if (= (:id %) task-id) (merge % updated-task) %) tasks)))
      (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update task")))))

(defn toggle-filter [filter-type id]
  (let [filter-key (case filter-type
                     CATEGORY-TYPE-PERSON :tasks-page/filter-people
                     CATEGORY-TYPE-PLACE :tasks-page/filter-places
                     CATEGORY-TYPE-PROJECT :tasks-page/filter-projects
                     CATEGORY-TYPE-GOAL :tasks-page/filter-goals)]
    (swap! app-state update filter-key
           #(if (contains? % id)
              (disj % id)
              (conj % id)))))

(defn clear-filter-people []
  (swap! app-state assoc :tasks-page/filter-people #{}))

(defn clear-filter-places []
  (swap! app-state assoc :tasks-page/filter-places #{}))

(defn clear-filter-projects []
  (swap! app-state assoc :tasks-page/filter-projects #{}))

(defn clear-filter-goals []
  (swap! app-state assoc :tasks-page/filter-goals #{}))

(defn set-importance-filter [level]
  (swap! app-state assoc :tasks-page/importance-filter level))

(defn clear-importance-filter []
  (swap! app-state assoc :tasks-page/importance-filter nil))

(defn clear-uncollapsed-task-filters []
  (let [collapsed (:tasks-page/collapsed-filters @app-state)
        all-filters #{:people :places :projects :goals}
        uncollapsed (clojure.set/difference all-filters collapsed)]
    (if (empty? uncollapsed)
      (swap! app-state assoc
             :tasks-page/filter-people #{}
             :tasks-page/filter-places #{}
             :tasks-page/filter-projects #{}
             :tasks-page/filter-goals #{}
             :tasks-page/category-search {:people "" :places "" :projects "" :goals ""}
             :tasks-page/importance-filter nil)
      (do
        (doseq [filter-key uncollapsed]
          (case filter-key
            :people (swap! app-state assoc :tasks-page/filter-people #{})
            :places (swap! app-state assoc :tasks-page/filter-places #{})
            :projects (swap! app-state assoc :tasks-page/filter-projects #{})
            :goals (swap! app-state assoc :tasks-page/filter-goals #{})))
        (swap! app-state assoc
               :tasks-page/collapsed-filters all-filters
               :tasks-page/category-search {:people "" :places "" :projects "" :goals ""}
               :tasks-page/importance-filter nil)))))

(defn toggle-filter-collapsed [filter-key]
  (let [was-collapsed (contains? (:tasks-page/collapsed-filters @app-state) filter-key)
        all-filters #{:people :places :projects :goals}
        others-to-collapse (disj all-filters filter-key)]
    (swap! app-state update :tasks-page/collapsed-filters
           (fn [collapsed]
             (if (contains? collapsed filter-key)
               (disj all-filters filter-key)
               (conj collapsed filter-key))))
    (when was-collapsed
      (swap! app-state update :tasks-page/category-search
             (fn [searches]
               (reduce #(assoc %1 %2 "") searches all-filters))))
    (js/setTimeout
     (fn []
       (when-let [el (.getElementById js/document
                                      (if was-collapsed
                                        (str "tasks-filter-" (name filter-key))
                                        "tasks-search"))]
         (.focus el)))
     0)))

(defn set-filter-search [search-term]
  (swap! app-state assoc :tasks-page/filter-search search-term))

(defn set-category-search [category-key search-term]
  (swap! app-state assoc-in [:tasks-page/category-search category-key] search-term))

(defn open-category-selector [selector-id]
  (swap! app-state assoc
         :category-selector/open selector-id
         :category-selector/search ""))

(defn close-category-selector []
  (swap! app-state assoc
         :category-selector/open nil
         :category-selector/search ""))

(defn set-category-selector-search [search-term]
  (swap! app-state assoc :category-selector/search search-term))

(defn focus-tasks-search []
  (js/setTimeout #(when-let [el (.getElementById js/document "tasks-search")]
                    (.focus el)) 0))

(def tab-initializers
  {:tasks (fn []
            (swap! app-state assoc :tasks-page/collapsed-filters #{:people :places :projects :goals})
            (focus-tasks-search))
   :today (fn []
            (swap! app-state assoc :today-page/collapsed-filters #{:places :projects}))
   :mail (fn []
           (when (is-admin?)
             (fetch-messages)))})

(defn set-active-tab [tab]
  (swap! app-state assoc
         :active-tab tab
         :category-selector/open nil
         :category-selector/search ""
         :tasks-page/category-search {:people "" :places "" :projects "" :goals ""}
         :today-page/category-search {:places "" :projects ""}
         :tasks-page/expanded-task nil
         :today-page/expanded-task nil)
  (when-let [init-fn (get tab-initializers tab)]
    (init-fn)))

(defn toggle-expanded [page-key task-id]
  (swap! app-state assoc
         page-key (if (= (get @app-state page-key) task-id) nil task-id)
         :category-selector/open nil
         :category-selector/search ""))

(defn set-editing [task-id]
  (swap! app-state assoc :editing-task task-id))

(defn clear-editing []
  (swap! app-state assoc :editing-task nil))

(defn prefix-matches? [title search-term]
  (let [title-lower (.toLowerCase title)
        search-lower (.toLowerCase search-term)
        words (.split title-lower #"\s+")]
    (some #(.startsWith % search-lower) words)))

(defn- matches-scope? [task mode strict?]
  (filters/matches-scope? task mode strict?))

(defn- scope-filtered-tasks []
  (let [mode (:work-private-mode @app-state)
        strict? (:strict-mode @app-state)]
    (filter #(matches-scope? % mode strict?) (:tasks @app-state))))

(defn- matches-importance-filter? [task importance-filter]
  (case importance-filter
    :important (contains? #{"important" "critical"} (:importance task))
    :critical (= "critical" (:importance task))
    true))

(defn filtered-tasks []
  (let [tasks (scope-filtered-tasks)
        filter-people (:tasks-page/filter-people @app-state)
        filter-places (:tasks-page/filter-places @app-state)
        filter-projects (:tasks-page/filter-projects @app-state)
        filter-goals (:tasks-page/filter-goals @app-state)
        filter-search (:tasks-page/filter-search @app-state)
        importance-filter (:tasks-page/importance-filter @app-state)
        matches-any? (fn [task-categories filter-ids]
                       (some #(contains? filter-ids (:id %)) task-categories))]
    (cond->> tasks
      (seq filter-people) (filter #(matches-any? (:people %) filter-people))
      (seq filter-places) (filter #(matches-any? (:places %) filter-places))
      (seq filter-projects) (filter #(matches-any? (:projects %) filter-projects))
      (seq filter-goals) (filter #(matches-any? (:goals %) filter-goals))
      (seq filter-search) (filter #(prefix-matches? (:title %) filter-search))
      importance-filter (filter #(matches-importance-filter? % importance-filter)))))

(defn set-sort-mode [mode]
  (swap! app-state assoc :sort-mode mode)
  (fetch-tasks))

(defn set-drag-task [task-id]
  (swap! app-state assoc :drag-task task-id))

(defn set-drag-over-task [task-id]
  (swap! app-state assoc :drag-over-task task-id))

(defn clear-drag-state []
  (swap! app-state assoc :drag-task nil :drag-over-task nil))

(defn reorder-task [task-id target-task-id position]
  (api/post-json (str "/api/tasks/" task-id "/reorder")
    {:target-task-id target-task-id :position position}
    (auth-headers)
    (fn [_]
      (clear-drag-state)
      (fetch-tasks))
    (fn [resp]
      (clear-drag-state)
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to reorder")))))

(defn set-drag-category [category-type category-id]
  (swap! app-state assoc :drag-category {:type category-type :id category-id}))

(defn set-drag-over-category [category-type category-id]
  (swap! app-state assoc :drag-over-category {:type category-type :id category-id}))

(defn clear-category-drag-state []
  (swap! app-state assoc :drag-category nil :drag-over-category nil))

(defn reorder-category [category-type category-id target-category-id position]
  (let [endpoint (case category-type
                   :people "/api/people/"
                   :places "/api/places/"
                   :projects "/api/projects/"
                   :goals "/api/goals/")
        fetch-fn (case category-type
                   :people fetch-people
                   :places fetch-places
                   :projects fetch-projects
                   :goals fetch-goals)]
    (api/post-json (str endpoint category-id "/reorder")
      {:target-category-id target-category-id :position position}
      (auth-headers)
      (fn [_]
        (clear-category-drag-state)
        (fetch-fn))
      (fn [resp]
        (clear-category-drag-state)
        (swap! app-state assoc :error (get-in resp [:response :error] "Failed to reorder"))))))

(defn set-task-due-date [task-id due-date]
  (api/put-json (str "/api/tasks/" task-id "/due-date")
    {:due-date due-date}
    (auth-headers)
    (fn [result]
      (swap! app-state update :tasks
             (fn [tasks]
               (mapv #(if (= (:id %) task-id)
                        (assoc % :due_date (:due_date result) :due_time (:due_time result) :modified_at (:modified_at result))
                        %)
                     tasks))))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to set due date")))))

(defn set-task-due-time [task-id due-time]
  (api/put-json (str "/api/tasks/" task-id "/due-time")
    {:due-time due-time}
    (auth-headers)
    (fn [result]
      (swap! app-state update :tasks
             (fn [tasks]
               (mapv #(if (= (:id %) task-id)
                        (assoc % :due_date (:due_date result) :due_time (:due_time result) :modified_at (:modified_at result))
                        %)
                     tasks))))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to set due time")))))

(defn set-confirm-delete-task [task]
  (swap! app-state assoc :confirm-delete-task task))

(defn clear-confirm-delete []
  (swap! app-state assoc :confirm-delete-task nil))

(defn delete-task [task-id]
  (api/delete-simple (str "/api/tasks/" task-id)
    (auth-headers)
    (fn [_]
      (swap! app-state
             (fn [state]
               (-> state
                   (update :tasks (fn [tasks] (filterv #(not= (:id %) task-id) tasks)))
                   (assoc :tasks-page/expanded-task nil
                          :today-page/expanded-task nil
                          :confirm-delete-task nil)))))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to delete task"))
      (clear-confirm-delete))))

(defn set-task-done [task-id done?]
  (api/put-json (str "/api/tasks/" task-id "/done")
    {:done done?}
    (auth-headers)
    (fn [_]
      (swap! app-state assoc
             :tasks-page/expanded-task nil
             :today-page/expanded-task nil)
      (fetch-tasks))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update task")))))

(defn set-upcoming-horizon [horizon]
  (swap! app-state assoc :upcoming-horizon horizon))

(defn toggle-today-excluded-place [place-id]
  (swap! app-state update :today-page/excluded-places
         #(if (contains? % place-id)
            (disj % place-id)
            (conj % place-id)))
  (recalculate-today-horizon))

(defn toggle-today-excluded-project [project-id]
  (swap! app-state update :today-page/excluded-projects
         #(if (contains? % project-id)
            (disj % project-id)
            (conj % project-id)))
  (recalculate-today-horizon))

(defn clear-today-excluded-places []
  (swap! app-state assoc :today-page/excluded-places #{})
  (recalculate-today-horizon))

(defn clear-today-excluded-projects []
  (swap! app-state assoc :today-page/excluded-projects #{})
  (recalculate-today-horizon))

(defn clear-uncollapsed-today-filters []
  (let [collapsed (:today-page/collapsed-filters @app-state)
        all-filters #{:places :projects}
        uncollapsed (clojure.set/difference all-filters collapsed)]
    (if (empty? uncollapsed)
      (do
        (swap! app-state assoc
               :today-page/excluded-places #{}
               :today-page/excluded-projects #{}
               :today-page/category-search {:places "" :projects ""})
        (recalculate-today-horizon))
      (do
        (doseq [filter-key uncollapsed]
          (case filter-key
            :places (swap! app-state assoc :today-page/excluded-places #{})
            :projects (swap! app-state assoc :today-page/excluded-projects #{})))
        (swap! app-state assoc
               :today-page/collapsed-filters all-filters
               :today-page/category-search {:places "" :projects ""})
        (recalculate-today-horizon)))))

(defn toggle-today-filter-collapsed [filter-key]
  (let [was-collapsed (contains? (:today-page/collapsed-filters @app-state) filter-key)
        all-filters #{:places :projects}]
    (swap! app-state update :today-page/collapsed-filters
           (fn [collapsed]
             (if (contains? collapsed filter-key)
               (disj all-filters filter-key)
               (conj collapsed filter-key))))
    (when was-collapsed
      (swap! app-state update :today-page/category-search
             (fn [searches]
               (reduce #(assoc %1 %2 "") searches all-filters)))
      (js/setTimeout
       (fn []
         (when-let [el (.getElementById js/document (str "today-filter-" (name filter-key)))]
           (.focus el)))
       0))))

(defn set-today-category-search [category-key search-term]
  (swap! app-state assoc-in [:today-page/category-search category-key] search-term))

(defn set-today-selected-view [view]
  (when (#{:urgent :upcoming} view)
    (swap! app-state assoc :today-page/selected-view view)))

(defn- apply-today-exclusion-filter [tasks]
  (filters/apply-exclusion-filter tasks
                                  (:today-page/excluded-places @app-state)
                                  (:today-page/excluded-projects @app-state)))

(def today-str date/today-str)
(def add-days date/add-days)
(def day-of-week date/day-of-week)
(def format-date-with-day date/format-date-with-day)
(def get-day-name date/get-day-name)
(def within-days? date/within-days?)
(def today-formatted date/today-formatted)
(def horizon-order date/horizon-order)
(def horizon-end-date date/horizon-end-date)

(defn count-upcoming-tasks-for-horizon [tasks horizon]
  (let [today (today-str)
        end-date (horizon-end-date horizon)]
    (count (filter (fn [task]
                     (and (:due_date task)
                          (> (:due_date task) today)
                          (<= (:due_date task) end-date)))
                   tasks))))

(defn calculate-best-horizon [tasks]
  (or (first (filter #(>= (count-upcoming-tasks-for-horizon tasks %) filters/target-upcoming-tasks-count) horizon-order))
      :eighteen-months))

(defn- recalculate-today-horizon []
  (let [tasks (:tasks @app-state)
        filtered-tasks (apply-today-exclusion-filter tasks)]
    (swap! app-state assoc :upcoming-horizon (calculate-best-horizon filtered-tasks))))

(defn- sort-by-date-and-time [tasks]
  (sort-by (juxt :due_date #(if (:due_time %) 1 0) :due_time) tasks))

;; Note: Backend also sorts by date+time in :due-date mode (db.clj).
;; Frontend re-sorts because Today view applies exclusion filters after fetch,
;; and we need consistent ordering regardless of how tasks arrived in state.

(defn overdue-tasks []
  (let [today (today-str)]
    (->> (scope-filtered-tasks)
         (filter #(and (:due_date %)
                       (< (:due_date %) today)))
         (apply-today-exclusion-filter)
         (sort-by-date-and-time))))

(defn today-tasks []
  (let [today (today-str)]
    (->> (scope-filtered-tasks)
         (filter #(= (:due_date %) today))
         (apply-today-exclusion-filter)
         (sort-by-date-and-time))))

(defn upcoming-tasks []
  (let [today (today-str)
        horizon (:upcoming-horizon @app-state)
        end-date (horizon-end-date horizon)]
    (->> (scope-filtered-tasks)
         (filter #(and (:due_date %)
                       (> (:due_date %) today)
                       (<= (:due_date %) end-date)))
         (apply-today-exclusion-filter)
         (sort-by-date-and-time))))

(defn fetch-available-users []
  (GET "/api/auth/available-users"
    {:response-format :json
     :keywords? true
     :handler (fn [users]
                (swap! app-state assoc :available-users users))
     :error-handler (fn [_]
                      (swap! app-state assoc :available-users []))}))

(defn toggle-user-switcher []
  (swap! app-state update :show-user-switcher not))

(defn close-user-switcher []
  (swap! app-state assoc :show-user-switcher false))

(defn switch-user [user]
  (swap! app-state merge
         initial-collection-state
         {:current-user user
          :show-user-switcher false
          :active-tab :today})
  (apply-user-language user)
  (fetch-tasks)
  (fetch-people)
  (fetch-places)
  (fetch-projects)
  (fetch-goals)
  (when (:is_admin user)
    (fetch-messages)
    (fetch-users)))

(defn fetch-users []
  (GET "/api/users"
    {:response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [users]
                (swap! app-state assoc :users users))
     :error-handler (fn [_]
                      (swap! app-state assoc :users []))}))

(defn add-user [username password on-success]
  (api/post-json "/api/users" {:username username :password password} (auth-headers)
    (fn [user]
      (swap! app-state update :users conj user)
      (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to add user")))))

(defn set-confirm-delete-user [user]
  (swap! app-state assoc :confirm-delete-user user))

(defn clear-confirm-delete-user []
  (swap! app-state assoc :confirm-delete-user nil))

(defn delete-user [user-id]
  (api/delete-simple (str "/api/users/" user-id)
    (auth-headers)
    (fn [_]
      (swap! app-state update :users
             (fn [users] (filterv #(not= (:id %) user-id) users)))
      (clear-confirm-delete-user))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to delete user"))
      (clear-confirm-delete-user))))

(defn set-editing-category [category-type id]
  (swap! app-state assoc :category-page/editing {:type category-type :id id}))

(defn clear-editing-category []
  (swap! app-state assoc :category-page/editing nil))

(defn update-person [id name description on-success]
  (api/put-json (str "/api/people/" id)
    {:name name :description description}
    (auth-headers)
    (fn [updated]
      (swap! app-state update :people
             (fn [items] (mapv #(if (= (:id %) id) updated %) items)))
      (fetch-tasks)
      (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update person")))))

(defn update-place [id name description on-success]
  (api/put-json (str "/api/places/" id)
    {:name name :description description}
    (auth-headers)
    (fn [updated]
      (swap! app-state update :places
             (fn [items] (mapv #(if (= (:id %) id) updated %) items)))
      (fetch-tasks)
      (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update place")))))

(defn update-project [id name description on-success]
  (api/put-json (str "/api/projects/" id)
    {:name name :description description}
    (auth-headers)
    (fn [updated]
      (swap! app-state update :projects
             (fn [items] (mapv #(if (= (:id %) id) updated %) items)))
      (fetch-tasks)
      (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update project")))))

(defn update-goal [id name description on-success]
  (api/put-json (str "/api/goals/" id)
    {:name name :description description}
    (auth-headers)
    (fn [updated]
      (swap! app-state update :goals
             (fn [items] (mapv #(if (= (:id %) id) updated %) items)))
      (fetch-tasks)
      (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update goal")))))

(defn set-confirm-delete-category [category-type category]
  (swap! app-state assoc :confirm-delete-category {:type category-type :category category}))

(defn clear-confirm-delete-category []
  (swap! app-state assoc :confirm-delete-category nil))

(defn- delete-category-entity [endpoint state-key error-msg id]
  (api/delete-simple (str endpoint id)
    (auth-headers)
    (fn [_]
      (swap! app-state update state-key
             (fn [items] (filterv #(not= (:id %) id) items)))
      (fetch-tasks)
      (clear-confirm-delete-category))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] error-msg))
      (clear-confirm-delete-category))))

(defn delete-person [id]
  (delete-category-entity "/api/people/" :people "Failed to delete person" id))

(defn delete-place [id]
  (delete-category-entity "/api/places/" :places "Failed to delete place" id))

(defn delete-project [id]
  (delete-category-entity "/api/projects/" :projects "Failed to delete project" id))

(defn delete-goal [id]
  (delete-category-entity "/api/goals/" :goals "Failed to delete goal" id))

(defn update-user-language [language]
  (api/put-json "/api/user/language" {:language language} (auth-headers)
    (fn [_]
      (i18n/set-language! language)
      (swap! app-state update :current-user assoc :language language)
      (let [user (:current-user @app-state)
            token (:token @app-state)]
        (save-auth-to-storage token user)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update language")))))

(defn set-work-private-mode [mode]
  (swap! app-state assoc :work-private-mode mode))

(defn toggle-strict-mode []
  (swap! app-state update :strict-mode not))

(defn toggle-dark-mode []
  (swap! app-state update :dark-mode not))

(add-watch app-state :dark-mode-sync
  (fn [_ _ old-state new-state]
    (when (not= (:dark-mode old-state) (:dark-mode new-state))
      (if (:dark-mode new-state)
        (.add (.-classList (.-documentElement js/document)) "dark-mode")
        (.remove (.-classList (.-documentElement js/document)) "dark-mode")))))

(defn set-task-scope [task-id scope]
  (api/put-json (str "/api/tasks/" task-id "/scope")
    {:scope scope}
    (auth-headers)
    (fn [result]
      (swap! app-state update :tasks
             (fn [tasks]
               (mapv #(if (= (:id %) task-id)
                        (assoc % :scope (:scope result) :modified_at (:modified_at result))
                        %)
                     tasks))))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update scope")))))

(defn export-data []
  (let [headers (auth-headers)
        url "/api/export"]
    (-> (js/fetch url (clj->js {:method "GET"
                                 :headers headers}))
        (.then (fn [response]
                 (if (.-ok response)
                   (-> (.blob response)
                       (.then (fn [blob]
                                (let [content-disposition (or (.get (.-headers response) "content-disposition") "")
                                      filename (if-let [match (re-find #"filename=\"([^\"]+)\"" content-disposition)]
                                                 (second match)
                                                 "export.zip")
                                      url (js/URL.createObjectURL blob)
                                      a (.createElement js/document "a")]
                                  (set! (.-href a) url)
                                  (set! (.-download a) filename)
                                  (.click a)
                                  (js/URL.revokeObjectURL url)))))
                   (swap! app-state assoc :error "Failed to export data"))))
        (.catch (fn [_]
                  (swap! app-state assoc :error "Failed to export data"))))))

(defn set-task-importance [task-id importance]
  (api/put-json (str "/api/tasks/" task-id "/importance")
    {:importance importance}
    (auth-headers)
    (fn [result]
      (swap! app-state update :tasks
             (fn [tasks]
               (mapv #(if (= (:id %) task-id)
                        (assoc % :importance (:importance result) :modified_at (:modified_at result))
                        %)
                     tasks))))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update importance")))))

(defn set-task-urgency [task-id urgency]
  (api/put-json (str "/api/tasks/" task-id "/urgency")
    {:urgency urgency}
    (auth-headers)
    (fn [result]
      (swap! app-state update :tasks
             (fn [tasks]
               (mapv #(if (= (:id %) task-id)
                        (assoc % :urgency (:urgency result) :modified_at (:modified_at result))
                        %)
                     tasks))))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update urgency")))))

(defn urgent-tasks []
  (->> (scope-filtered-tasks)
       (filter #(contains? #{"urgent" "superurgent"} (:urgency %)))
       (apply-today-exclusion-filter)
       (sort-by (juxt #(if (= "superurgent" (:urgency %)) 0 1) :sort_order))))

(defn fetch-messages []
  (let [request-id (:mail-page/fetch-request-id (swap! app-state update :mail-page/fetch-request-id inc))
        sort-mode (name (:mail-page/sort-mode @app-state))]
    (GET (str "/api/messages?sort=" sort-mode)
      {:response-format :json
       :keywords? true
       :headers (auth-headers)
       :handler (fn [messages]
                  (when (= request-id (:mail-page/fetch-request-id @app-state))
                    (swap! app-state assoc :messages messages)))
       :error-handler (fn [_]
                        (when (= request-id (:mail-page/fetch-request-id @app-state))
                          (swap! app-state assoc :messages [])))})))

(defn set-mail-sort-mode [mode]
  (swap! app-state assoc :mail-page/sort-mode mode)
  (fetch-messages))

(defn set-expanded-message [id]
  (swap! app-state assoc :mail-page/expanded-message id))

(defn set-message-done [message-id done?]
  (api/put-json (str "/api/messages/" message-id "/done")
    {:done done?}
    (auth-headers)
    (fn [_] (fetch-messages))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update message")))))

(defn set-confirm-delete-message [message]
  (swap! app-state assoc :confirm-delete-message message))

(defn clear-confirm-delete-message []
  (swap! app-state assoc :confirm-delete-message nil))

(defn delete-message [message-id]
  (api/delete-simple (str "/api/messages/" message-id)
    (auth-headers)
    (fn [_]
      (swap! app-state update :messages
             (fn [messages] (filterv #(not= (:id %) message-id) messages)))
      (clear-confirm-delete-message))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to delete message"))
      (clear-confirm-delete-message))))

(defn is-admin? []
  (let [current-user (:current-user @app-state)]
    (or (nil? (:id current-user))
        (:is_admin current-user))))
