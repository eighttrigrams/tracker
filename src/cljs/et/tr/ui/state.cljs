(ns et.tr.ui.state
  (:require [reagent.core :as r]
            [ajax.core :refer [GET POST PUT DELETE]]))

(defonce app-state (r/atom {:tasks []
                            :people []
                            :places []
                            :projects []
                            :goals []
                            :users []
                            :filter-people #{}
                            :filter-places #{}
                            :filter-projects #{}
                            :filter-goals #{}
                            :filter-search ""
                            :category-search {:people "" :places "" :projects "" :goals ""}
                            :expanded-task nil
                            :editing-task nil
                            :confirm-delete-task nil
                            :pending-new-task nil
                            :active-tab :today
                            :auth-required? nil
                            :logged-in? false
                            :token nil
                            :current-user nil
                            :error nil
                            :collapsed-filters #{:people :places :projects :goals}
                            :sort-mode :recent
                            :drag-task nil
                            :drag-over-task nil
                            :upcoming-horizon :week}))

(defn auth-headers []
  (let [token (:token @app-state)
        current-user (:current-user @app-state)
        user-id (:id current-user)]
    (cond-> {}
      token (assoc "Authorization" (str "Bearer " token))
      (and (not token) current-user) (assoc "X-User-Id" (if (nil? user-id) "null" (str user-id))))))

(declare fetch-tasks)
(declare fetch-users)

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

(defn fetch-auth-required []
  (GET "/api/auth/required"
    {:response-format :json
     :keywords? true
     :handler (fn [resp]
                (swap! app-state assoc :auth-required? (:required resp))
                (when-not (:required resp)
                  (swap! app-state assoc :logged-in? true)
                  (fetch-tasks))
                (when (:required resp)
                  (let [{:keys [token user]} (load-auth-from-storage)]
                    (when (and token user)
                      (swap! app-state assoc
                             :logged-in? true
                             :token token
                             :current-user user)
                      (fetch-tasks)
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

(defn fetch-tasks []
  (let [sort-mode (name (:sort-mode @app-state))]
    (GET (str "/api/tasks?sort=" sort-mode)
      {:response-format :json
       :keywords? true
       :headers (auth-headers)
       :handler (fn [tasks]
                  (swap! app-state assoc :tasks tasks))})))

(defn has-active-filters? []
  (let [{:keys [filter-people filter-places filter-projects filter-goals]} @app-state]
    (or (seq filter-people) (seq filter-places) (seq filter-projects) (seq filter-goals))))

(defn add-task-with-categories [title categories on-success]
  (POST "/api/tasks"
    {:params {:title title}
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
  (let [{:keys [filter-people filter-places filter-projects filter-goals]} @app-state]
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
  (if (has-active-filters?)
    (set-pending-new-task title on-success)
    (POST "/api/tasks"
      {:params {:title title}
       :format :json
       :response-format :json
       :keywords? true
       :headers (auth-headers)
       :handler (fn [task]
                  (swap! app-state update :tasks #(cons task %))
                  (when on-success (on-success)))
       :error-handler (fn [resp]
                        (swap! app-state assoc :error (get-in resp [:response :error] "Failed to add task")))})))

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
  (swap! app-state update :filter-people
         #(if (contains? % person-id)
            (disj % person-id)
            (conj % person-id))))

(defn toggle-filter-place [place-id]
  (swap! app-state update :filter-places
         #(if (contains? % place-id)
            (disj % place-id)
            (conj % place-id))))

(defn toggle-filter-project [project-id]
  (swap! app-state update :filter-projects
         #(if (contains? % project-id)
            (disj % project-id)
            (conj % project-id))))

(defn toggle-filter-goal [goal-id]
  (swap! app-state update :filter-goals
         #(if (contains? % goal-id)
            (disj % goal-id)
            (conj % goal-id))))

(defn clear-filter-people []
  (swap! app-state assoc :filter-people #{}))

(defn clear-filter-places []
  (swap! app-state assoc :filter-places #{}))

(defn clear-filter-projects []
  (swap! app-state assoc :filter-projects #{}))

(defn clear-filter-goals []
  (swap! app-state assoc :filter-goals #{}))

(defn toggle-filter-collapsed [filter-key]
  (swap! app-state update :collapsed-filters
         (fn [collapsed]
           (if (contains? collapsed filter-key)
             (disj #{:people :places :projects :goals} filter-key)
             (conj collapsed filter-key)))))

(defn set-filter-search [search-term]
  (swap! app-state assoc :filter-search search-term))

(defn set-category-search [category-key search-term]
  (swap! app-state assoc-in [:category-search category-key] search-term))

(defn set-active-tab [tab]
  (swap! app-state assoc :active-tab tab))

(defn toggle-expanded [task-id]
  (swap! app-state update :expanded-task #(if (= % task-id) nil task-id)))

(defn set-editing [task-id]
  (swap! app-state assoc :editing-task task-id))

(defn clear-editing []
  (swap! app-state assoc :editing-task nil))

(defn prefix-matches? [title search-term]
  (let [title-lower (.toLowerCase title)
        search-lower (.toLowerCase search-term)
        words (.split title-lower #"\s+")]
    (some #(.startsWith % search-lower) words)))

(defn filtered-tasks []
  (let [{:keys [tasks filter-people filter-places filter-projects filter-goals filter-search]} @app-state
        matches-any? (fn [task-categories filter-ids]
                       (some #(contains? filter-ids (:id %)) task-categories))]
    (cond->> tasks
      (seq filter-people) (filter #(matches-any? (:people %) filter-people))
      (seq filter-places) (filter #(matches-any? (:places %) filter-places))
      (seq filter-projects) (filter #(matches-any? (:projects %) filter-projects))
      (seq filter-goals) (filter #(matches-any? (:goals %) filter-goals))
      (seq filter-search) (filter #(prefix-matches? (:title %) filter-search)))))

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
                                  (assoc % :due_date (:due_date result))
                                  %)
                               tasks))))
     :error-handler (fn [resp]
                      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to set due date")))}))

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

(defn set-upcoming-horizon [horizon]
  (swap! app-state assoc :upcoming-horizon horizon))

(defn today-str []
  (.substring (.toISOString (js/Date.)) 0 10))

(defn add-days [date-str days]
  (let [d (js/Date. date-str)]
    (.setDate d (+ (.getDate d) days))
    (.substring (.toISOString d) 0 10)))

(defn horizon-end-date [horizon]
  (let [today (today-str)]
    (case horizon
      :week (add-days today 7)
      :month (add-days today 30)
      :year (add-days today 365)
      :two-years (add-days today 730)
      (add-days today 7))))

(defn overdue-tasks []
  (let [today (today-str)]
    (->> (:tasks @app-state)
         (filter #(and (:due_date %)
                       (< (:due_date %) today)))
         (sort-by :due_date))))

(defn today-tasks []
  (let [today (today-str)]
    (->> (:tasks @app-state)
         (filter #(= (:due_date %) today))
         (sort-by :due_date))))

(defn upcoming-tasks []
  (let [today (today-str)
        horizon (:upcoming-horizon @app-state)
        end-date (horizon-end-date horizon)]
    (->> (:tasks @app-state)
         (filter #(and (:due_date %)
                       (> (:due_date %) today)
                       (<= (:due_date %) end-date)))
         (sort-by :due_date))))

(defn is-admin? []
  (true? (get-in @app-state [:current-user :is_admin])))

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

(defn delete-user [user-id]
  (DELETE (str "/api/users/" user-id)
    {:format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [_]
                (swap! app-state update :users
                       (fn [users] (filterv #(not= (:id %) user-id) users))))
     :error-handler (fn [resp]
                      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to delete user")))}))
