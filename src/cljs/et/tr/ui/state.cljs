(ns et.tr.ui.state
  (:require [reagent.core :as r]
            [ajax.core :refer [GET POST PUT DELETE]]))

(defonce app-state (r/atom {:tasks []
                            :people []
                            :places []
                            :projects []
                            :goals []
                            :filter-people #{}
                            :filter-places #{}
                            :filter-projects #{}
                            :filter-goals #{}
                            :filter-search ""
                            :expanded-task nil
                            :editing-task nil
                            :confirm-delete-task nil
                            :active-tab :tasks
                            :auth-required? nil
                            :logged-in? false
                            :token nil
                            :error nil
                            :collapsed-filters #{:people :places :projects :goals}
                            :sort-mode :recent
                            :drag-task nil
                            :drag-over-task nil}))

(defn auth-headers []
  (when-let [token (:token @app-state)]
    {"Authorization" (str "Bearer " token)}))

(declare fetch-tasks)

(defn fetch-auth-required []
  (GET "/api/auth/required"
    {:response-format :json
     :keywords? true
     :handler (fn [resp]
                (swap! app-state assoc :auth-required? (:required resp))
                (when-not (:required resp)
                  (swap! app-state assoc :logged-in? true)
                  (fetch-tasks)))}))

(defn login [password on-success]
  (POST "/api/auth/login"
    {:params {:email "admin" :password password}
     :format :json
     :response-format :json
     :keywords? true
     :handler (fn [resp]
                (swap! app-state assoc
                       :logged-in? true
                       :token (:token resp)
                       :error nil)
                (when on-success (on-success)))
     :error-handler (fn [_]
                      (swap! app-state assoc :error "Invalid password"))}))

(defn fetch-tasks []
  (let [sort-mode (name (:sort-mode @app-state))]
    (GET (str "/api/tasks?sort=" sort-mode)
      {:response-format :json
       :keywords? true
       :handler (fn [tasks]
                  (swap! app-state assoc :tasks tasks))})))

(defn add-task [title on-success]
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
                      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to add task")))}))

(defn fetch-people []
  (GET "/api/people"
    {:response-format :json
     :keywords? true
     :handler (fn [people]
                (swap! app-state assoc :people people))}))

(defn fetch-places []
  (GET "/api/places"
    {:response-format :json
     :keywords? true
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
     :handler (fn [projects]
                (swap! app-state assoc :projects projects))}))

(defn fetch-goals []
  (GET "/api/goals"
    {:response-format :json
     :keywords? true
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
