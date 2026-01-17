(ns et.tr.ui.state
  (:require [reagent.core :as r]
            [ajax.core :refer [GET POST PUT DELETE]]))

(defonce app-state (r/atom {:items []
                            :people []
                            :places []
                            :projects []
                            :goals []
                            :filter-people #{}
                            :filter-places #{}
                            :filter-projects #{}
                            :filter-goals #{}
                            :filter-search ""
                            :expanded-item nil
                            :editing-item nil
                            :active-tab :tasks
                            :auth-required? nil
                            :logged-in? false
                            :token nil
                            :error nil
                            :collapsed-filters #{:people :places :projects :goals}
                            :sort-mode :recent
                            :drag-item nil
                            :drag-over-item nil}))

(defn auth-headers []
  (when-let [token (:token @app-state)]
    {"Authorization" (str "Bearer " token)}))

(declare fetch-items)

(defn fetch-auth-required []
  (GET "/api/auth/required"
    {:response-format :json
     :keywords? true
     :handler (fn [resp]
                (swap! app-state assoc :auth-required? (:required resp))
                (when-not (:required resp)
                  (swap! app-state assoc :logged-in? true)
                  (fetch-items)))}))

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

(defn fetch-items []
  (let [sort-mode (name (:sort-mode @app-state))]
    (GET (str "/api/items?sort=" sort-mode)
      {:response-format :json
       :keywords? true
       :handler (fn [items]
                  (swap! app-state assoc :items items))})))

(defn add-item [title on-success]
  (POST "/api/items"
    {:params {:title title}
     :format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [item]
                (swap! app-state update :items #(cons item %))
                (when on-success (on-success)))
     :error-handler (fn [resp]
                      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to add item")))}))

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

(defn tag-item [item-id tag-type tag-id]
  (POST (str "/api/items/" item-id "/tag")
    {:params {:tag-type tag-type :tag-id tag-id}
     :format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [_]
                (fetch-items))}))

(defn untag-item [item-id tag-type tag-id]
  (DELETE (str "/api/items/" item-id "/tag")
    {:params {:tag-type tag-type :tag-id tag-id}
     :format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [_]
                (fetch-items))}))

(defn update-item [item-id title description on-success]
  (PUT (str "/api/items/" item-id)
    {:params {:title title :description description}
     :format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [updated-item]
                (swap! app-state update :items
                       (fn [items]
                         (mapv #(if (= (:id %) item-id)
                                  (merge % updated-item)
                                  %)
                               items)))
                (when on-success (on-success)))
     :error-handler (fn [resp]
                      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update item")))}))

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

(defn toggle-expanded [item-id]
  (swap! app-state update :expanded-item #(if (= % item-id) nil item-id)))

(defn set-editing [item-id]
  (swap! app-state assoc :editing-item item-id))

(defn clear-editing []
  (swap! app-state assoc :editing-item nil))

(defn prefix-matches? [title search-term]
  (let [title-lower (.toLowerCase title)
        search-lower (.toLowerCase search-term)
        words (.split title-lower #"\s+")]
    (some #(.startsWith % search-lower) words)))

(defn filtered-items []
  (let [{:keys [items filter-people filter-places filter-projects filter-goals filter-search]} @app-state
        matches-any? (fn [item-tags filter-ids]
                       (some #(contains? filter-ids (:id %)) item-tags))]
    (cond->> items
      (seq filter-people) (filter #(matches-any? (:people %) filter-people))
      (seq filter-places) (filter #(matches-any? (:places %) filter-places))
      (seq filter-projects) (filter #(matches-any? (:projects %) filter-projects))
      (seq filter-goals) (filter #(matches-any? (:goals %) filter-goals))
      (seq filter-search) (filter #(prefix-matches? (:title %) filter-search)))))

(defn set-sort-mode [mode]
  (swap! app-state assoc :sort-mode mode)
  (fetch-items))

(defn set-drag-item [item-id]
  (swap! app-state assoc :drag-item item-id))

(defn set-drag-over-item [item-id]
  (swap! app-state assoc :drag-over-item item-id))

(defn clear-drag-state []
  (swap! app-state assoc :drag-item nil :drag-over-item nil))

(defn reorder-item [item-id target-item-id position]
  (POST (str "/api/items/" item-id "/reorder")
    {:params {:target-item-id target-item-id :position position}
     :format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [_]
                (clear-drag-state)
                (fetch-items))
     :error-handler (fn [resp]
                      (clear-drag-state)
                      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to reorder")))}))
