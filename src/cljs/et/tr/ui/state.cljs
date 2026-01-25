(ns et.tr.ui.state
  (:require [reagent.core :as r]
            [ajax.core :refer [GET POST PUT DELETE]]
            [clojure.string :as str]
            [et.tr.filters :as filters]
            [et.tr.i18n :as i18n]))

(defonce app-state (r/atom {:tasks []
                            :people []
                            :places []
                            :projects []
                            :goals []
                            :users []
                            :available-users []
                            :show-user-switcher false
                            :tasks-page/filter-people #{}
                            :tasks-page/filter-places #{}
                            :tasks-page/filter-projects #{}
                            :tasks-page/filter-goals #{}
                            :tasks-page/filter-search ""
                            :tasks-page/category-search {:people "" :places "" :projects "" :goals ""}
                            :tasks-page/importance-filter nil
                            :expanded-task nil
                            :editing-task nil
                            :category-page/editing nil
                            :confirm-delete-task nil
                            :confirm-delete-user nil
                            :pending-new-task nil
                            :active-tab :today
                            :auth-required? nil
                            :logged-in? false
                            :token nil
                            :current-user nil
                            :error nil
                            :tasks-page/collapsed-filters #{:people :places :projects :goals}
                            :sort-mode :manual
                            :drag-task nil
                            :drag-over-task nil
                            :drag-category nil
                            :drag-over-category nil
                            :upcoming-horizon nil
                            :today-page/excluded-places #{}
                            :today-page/excluded-projects #{}
                            :today-page/collapsed-filters #{:places :projects}
                            :today-page/category-search {:places "" :projects ""}
                            :today-page/expanded-task nil
                            :category-selector/open nil
                            :category-selector/search ""
                            :work-private-mode :both
                            :strict-mode false
                            :dark-mode false}))

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
(declare calculate-best-horizon)
(declare recalculate-today-horizon)

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
                    (fetch-users))))
     :error-handler (fn [resp]
                      (swap! app-state assoc :error (get-in resp [:response :error] "Invalid credentials")))}))

(defn logout []
  (clear-auth-from-storage)
  (swap! app-state assoc
         :logged-in? false
         :token nil
         :current-user nil
         :tasks []
         :people []
         :places []
         :projects []
         :goals []
         :users []))

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

(defn add-task-with-categories [title categories on-success]
  (POST "/api/tasks"
    {:params {:title title :scope (current-scope)}
     :format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [task]
                (let [task-id (:id task)
                      {:keys [people places projects goals]} categories
                      categorize-all (fn []
                                       (doseq [id people]
                                         (POST (str "/api/tasks/" task-id "/categorize")
                                           {:params {:category-type "person" :category-id id}
                                            :format :json
                                            :response-format :json
                                            :keywords? true
                                            :headers (auth-headers)
                                            :handler (fn [_])}))
                                       (doseq [id places]
                                         (POST (str "/api/tasks/" task-id "/categorize")
                                           {:params {:category-type "place" :category-id id}
                                            :format :json
                                            :response-format :json
                                            :keywords? true
                                            :headers (auth-headers)
                                            :handler (fn [_])}))
                                       (doseq [id projects]
                                         (POST (str "/api/tasks/" task-id "/categorize")
                                           {:params {:category-type "project" :category-id id}
                                            :format :json
                                            :response-format :json
                                            :keywords? true
                                            :headers (auth-headers)
                                            :handler (fn [_])}))
                                       (doseq [id goals]
                                         (POST (str "/api/tasks/" task-id "/categorize")
                                           {:params {:category-type "goal" :category-id id}
                                            :format :json
                                            :response-format :json
                                            :keywords? true
                                            :headers (auth-headers)
                                            :handler (fn [_])}))
                                       (js/setTimeout fetch-tasks 500))]
                  (swap! app-state update :tasks #(cons task %))
                  (categorize-all)
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
              "person" :people
              "place" :places
              "project" :projects
              "goal" :goals
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
  (GET "/api/people"
    {:response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [people]
                (swap! app-state assoc :people people))}))

(defn fetch-places []
  (GET "/api/places"
    {:response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [places]
                (swap! app-state assoc :places places))}))

(defn add-person [name on-success]
  (POST "/api/people"
    {:params {:name name}
     :format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [person]
                (swap! app-state update :people conj person)
                (when on-success (on-success)))
     :error-handler (fn [resp]
                      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to add person")))}))

(defn add-place [name on-success]
  (POST "/api/places"
    {:params {:name name}
     :format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [place]
                (swap! app-state update :places conj place)
                (when on-success (on-success)))
     :error-handler (fn [resp]
                      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to add place")))}))

(defn fetch-projects []
  (GET "/api/projects"
    {:response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [projects]
                (swap! app-state assoc :projects projects))}))

(defn fetch-goals []
  (GET "/api/goals"
    {:response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [goals]
                (swap! app-state assoc :goals goals))}))

(defn add-project [name on-success]
  (POST "/api/projects"
    {:params {:name name}
     :format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [project]
                (swap! app-state update :projects conj project)
                (when on-success (on-success)))
     :error-handler (fn [resp]
                      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to add project")))}))

(defn add-goal [name on-success]
  (POST "/api/goals"
    {:params {:name name}
     :format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [goal]
                (swap! app-state update :goals conj goal)
                (when on-success (on-success)))
     :error-handler (fn [resp]
                      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to add goal")))}))

(defn categorize-task [task-id category-type category-id]
  (POST (str "/api/tasks/" task-id "/categorize")
    {:params {:category-type category-type :category-id category-id}
     :format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [_]
                (fetch-tasks))}))

(defn uncategorize-task [task-id category-type category-id]
  (DELETE (str "/api/tasks/" task-id "/categorize")
    {:params {:category-type category-type :category-id category-id}
     :format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [_]
                (fetch-tasks))}))

(defn update-task [task-id title description on-success]
  (PUT (str "/api/tasks/" task-id)
    {:params {:title title :description description}
     :format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [updated-task]
                (swap! app-state update :tasks
                       (fn [tasks]
                         (mapv #(if (= (:id %) task-id)
                                  (merge % updated-task)
                                  %)
                               tasks)))
                (when on-success (on-success)))
     :error-handler (fn [resp]
                      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update task")))}))

(defn toggle-filter-person [person-id]
  (swap! app-state update :tasks-page/filter-people
         #(if (contains? % person-id)
            (disj % person-id)
            (conj % person-id))))

(defn toggle-filter-place [place-id]
  (swap! app-state update :tasks-page/filter-places
         #(if (contains? % place-id)
            (disj % place-id)
            (conj % place-id))))

(defn toggle-filter-project [project-id]
  (swap! app-state update :tasks-page/filter-projects
         #(if (contains? % project-id)
            (disj % project-id)
            (conj % project-id))))

(defn toggle-filter-goal [goal-id]
  (swap! app-state update :tasks-page/filter-goals
         #(if (contains? % goal-id)
            (disj % goal-id)
            (conj % goal-id))))

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

(defn set-active-tab [tab]
  (swap! app-state assoc
         :active-tab tab
         :category-selector/open nil
         :category-selector/search ""
         :tasks-page/category-search {:people "" :places "" :projects "" :goals ""}
         :today-page/category-search {:places "" :projects ""})
  (when (= tab :tasks)
    (swap! app-state assoc :tasks-page/collapsed-filters #{:people :places :projects :goals})
    (focus-tasks-search))
  (when (= tab :today)
    (swap! app-state assoc :today-page/collapsed-filters #{:places :projects})))

(defn toggle-expanded [task-id]
  (swap! app-state assoc
         :expanded-task (if (= (:expanded-task @app-state) task-id) nil task-id)
         :category-selector/open nil
         :category-selector/search ""))

(defn toggle-today-expanded [task-id]
  (swap! app-state update :today-page/expanded-task #(if (= % task-id) nil task-id)))

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
  (POST (str "/api/tasks/" task-id "/reorder")
    {:params {:target-task-id target-task-id :position position}
     :format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [_]
                (clear-drag-state)
                (fetch-tasks))
     :error-handler (fn [resp]
                      (clear-drag-state)
                      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to reorder")))}))

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
    (POST (str endpoint category-id "/reorder")
      {:params {:target-category-id target-category-id :position position}
       :format :json
       :response-format :json
       :keywords? true
       :headers (auth-headers)
       :handler (fn [_]
                  (clear-category-drag-state)
                  (fetch-fn))
       :error-handler (fn [resp]
                        (clear-category-drag-state)
                        (swap! app-state assoc :error (get-in resp [:response :error] "Failed to reorder")))})))

(defn set-task-due-date [task-id due-date]
  (PUT (str "/api/tasks/" task-id "/due-date")
    {:params {:due-date due-date}
     :format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [result]
                (swap! app-state update :tasks
                       (fn [tasks]
                         (mapv #(if (= (:id %) task-id)
                                  (assoc % :due_date (:due_date result) :due_time (:due_time result) :modified_at (:modified_at result))
                                  %)
                               tasks))))
     :error-handler (fn [resp]
                      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to set due date")))}))

(defn set-task-due-time [task-id due-time]
  (PUT (str "/api/tasks/" task-id "/due-time")
    {:params {:due-time due-time}
     :format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [result]
                (swap! app-state update :tasks
                       (fn [tasks]
                         (mapv #(if (= (:id %) task-id)
                                  (assoc % :due_date (:due_date result) :due_time (:due_time result) :modified_at (:modified_at result))
                                  %)
                               tasks))))
     :error-handler (fn [resp]
                      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to set due time")))}))

(defn set-confirm-delete-task [task]
  (swap! app-state assoc :confirm-delete-task task))

(defn clear-confirm-delete []
  (swap! app-state assoc :confirm-delete-task nil))

(defn delete-task [task-id]
  (DELETE (str "/api/tasks/" task-id)
    {:format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [_]
                (swap! app-state update :tasks
                       (fn [tasks] (filterv #(not= (:id %) task-id) tasks)))
                (swap! app-state assoc :expanded-task nil :confirm-delete-task nil))
     :error-handler (fn [resp]
                      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to delete task"))
                      (clear-confirm-delete))}))

(defn set-task-done [task-id done?]
  (PUT (str "/api/tasks/" task-id "/done")
    {:params {:done done?}
     :format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [_]
                (swap! app-state assoc :expanded-task nil)
                (fetch-tasks))
     :error-handler (fn [resp]
                      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update task")))}))

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

(defn- apply-today-exclusion-filter [tasks]
  (filters/apply-exclusion-filter tasks
                                  (:today-page/excluded-places @app-state)
                                  (:today-page/excluded-projects @app-state)))

(defn today-str []
  (.substring (.toISOString (js/Date.)) 0 10))

(defn add-days [date-str days]
  (let [d (js/Date. (str date-str "T12:00:00"))]
    (.setDate d (+ (.getDate d) days))
    (.substring (.toISOString d) 0 10)))

(defn day-of-week [date-str]
  (let [d (js/Date. (str date-str "T12:00:00"))]
    (.getDay d)))

(defn- day-number->translation-key [day-num]
  (case day-num
    0 :date/sunday
    1 :date/monday
    2 :date/tuesday
    3 :date/wednesday
    4 :date/thursday
    5 :date/friday
    6 :date/saturday
    nil))

(defn format-date-with-day [date-str]
  (when date-str
    (if-let [day-key (day-number->translation-key (day-of-week date-str))]
      (str date-str ", " (i18n/t day-key))
      date-str)))

(defn get-day-name [date-str]
  (when date-str
    (when-let [day-key (day-number->translation-key (day-of-week date-str))]
      (i18n/t day-key))))

(defn within-days? [date-str days]
  (when date-str
    (let [today (today-str)
          end-date (add-days today days)]
      (and (> date-str today)
           (<= date-str end-date)))))

(defn today-formatted []
  (let [today (today-str)
        day-key (day-number->translation-key (day-of-week today))]
    (str (i18n/t :today/today) ", " (i18n/t day-key) ", " today)))

(def horizon-order [:three-days :week :month :three-months :year :eighteen-months])

(defn horizon-end-date [horizon]
  (let [today (today-str)]
    (case horizon
      :three-days (add-days today 3)
      :week (add-days today 7)
      :month (add-days today 30)
      :three-months (add-days today 90)
      :year (add-days today 365)
      :eighteen-months (add-days today 548)
      (add-days today 7))))

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

(defn is-admin? []
  (true? (get-in @app-state [:current-user :is_admin])))

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
  (swap! app-state assoc
         :current-user user
         :show-user-switcher false
         :tasks []
         :people []
         :places []
         :projects []
         :goals []
         :upcoming-horizon nil
         :active-tab :today)
  (apply-user-language user)
  (fetch-tasks)
  (fetch-people)
  (fetch-places)
  (fetch-projects)
  (fetch-goals)
  (when (:is_admin user)
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
  (POST "/api/users"
    {:params {:username username :password password}
     :format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [user]
                (swap! app-state update :users conj user)
                (when on-success (on-success)))
     :error-handler (fn [resp]
                      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to add user")))}))

(defn set-confirm-delete-user [user]
  (swap! app-state assoc :confirm-delete-user user))

(defn clear-confirm-delete-user []
  (swap! app-state assoc :confirm-delete-user nil))

(defn delete-user [user-id]
  (DELETE (str "/api/users/" user-id)
    {:format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [_]
                (swap! app-state update :users
                       (fn [users] (filterv #(not= (:id %) user-id) users)))
                (clear-confirm-delete-user))
     :error-handler (fn [resp]
                      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to delete user"))
                      (clear-confirm-delete-user))}))

(defn set-editing-category [category-type id]
  (swap! app-state assoc :category-page/editing {:type category-type :id id}))

(defn clear-editing-category []
  (swap! app-state assoc :category-page/editing nil))

(defn update-person [id name description on-success]
  (PUT (str "/api/people/" id)
    {:params {:name name :description description}
     :format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [updated]
                (swap! app-state update :people
                       (fn [items] (mapv #(if (= (:id %) id) updated %) items)))
                (fetch-tasks)
                (when on-success (on-success)))
     :error-handler (fn [resp]
                      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update person")))}))

(defn update-place [id name description on-success]
  (PUT (str "/api/places/" id)
    {:params {:name name :description description}
     :format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [updated]
                (swap! app-state update :places
                       (fn [items] (mapv #(if (= (:id %) id) updated %) items)))
                (fetch-tasks)
                (when on-success (on-success)))
     :error-handler (fn [resp]
                      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update place")))}))

(defn update-project [id name description on-success]
  (PUT (str "/api/projects/" id)
    {:params {:name name :description description}
     :format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [updated]
                (swap! app-state update :projects
                       (fn [items] (mapv #(if (= (:id %) id) updated %) items)))
                (fetch-tasks)
                (when on-success (on-success)))
     :error-handler (fn [resp]
                      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update project")))}))

(defn update-goal [id name description on-success]
  (PUT (str "/api/goals/" id)
    {:params {:name name :description description}
     :format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [updated]
                (swap! app-state update :goals
                       (fn [items] (mapv #(if (= (:id %) id) updated %) items)))
                (fetch-tasks)
                (when on-success (on-success)))
     :error-handler (fn [resp]
                      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update goal")))}))

(defn set-confirm-delete-category [category-type category]
  (swap! app-state assoc :confirm-delete-category {:type category-type :category category}))

(defn clear-confirm-delete-category []
  (swap! app-state assoc :confirm-delete-category nil))

(defn delete-person [id]
  (DELETE (str "/api/people/" id)
    {:format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [_]
                (swap! app-state update :people
                       (fn [items] (filterv #(not= (:id %) id) items)))
                (fetch-tasks)
                (clear-confirm-delete-category))
     :error-handler (fn [resp]
                      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to delete person"))
                      (clear-confirm-delete-category))}))

(defn delete-place [id]
  (DELETE (str "/api/places/" id)
    {:format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [_]
                (swap! app-state update :places
                       (fn [items] (filterv #(not= (:id %) id) items)))
                (fetch-tasks)
                (clear-confirm-delete-category))
     :error-handler (fn [resp]
                      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to delete place"))
                      (clear-confirm-delete-category))}))

(defn delete-project [id]
  (DELETE (str "/api/projects/" id)
    {:format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [_]
                (swap! app-state update :projects
                       (fn [items] (filterv #(not= (:id %) id) items)))
                (fetch-tasks)
                (clear-confirm-delete-category))
     :error-handler (fn [resp]
                      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to delete project"))
                      (clear-confirm-delete-category))}))

(defn delete-goal [id]
  (DELETE (str "/api/goals/" id)
    {:format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [_]
                (swap! app-state update :goals
                       (fn [items] (filterv #(not= (:id %) id) items)))
                (fetch-tasks)
                (clear-confirm-delete-category))
     :error-handler (fn [resp]
                      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to delete goal"))
                      (clear-confirm-delete-category))}))

(defn update-user-language [language]
  (PUT "/api/user/language"
    {:params {:language language}
     :format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [result]
                (i18n/set-language! language)
                (swap! app-state update :current-user assoc :language language)
                (let [user (:current-user @app-state)
                      token (:token @app-state)]
                  (save-auth-to-storage token user)))
     :error-handler (fn [resp]
                      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update language")))}))

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
  (PUT (str "/api/tasks/" task-id "/scope")
    {:params {:scope scope}
     :format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [result]
                (swap! app-state update :tasks
                       (fn [tasks]
                         (mapv #(if (= (:id %) task-id)
                                  (assoc % :scope (:scope result) :modified_at (:modified_at result))
                                  %)
                               tasks))))
     :error-handler (fn [resp]
                      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update scope")))}))

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
  (PUT (str "/api/tasks/" task-id "/importance")
    {:params {:importance importance}
     :format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [result]
                (swap! app-state update :tasks
                       (fn [tasks]
                         (mapv #(if (= (:id %) task-id)
                                  (assoc % :importance (:importance result) :modified_at (:modified_at result))
                                  %)
                               tasks))))
     :error-handler (fn [resp]
                      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update importance")))}))
