(ns et.tr.ui.state.issues
  (:require [ajax.core :refer [GET POST]]
            [clojure.string]
            [reagent.core :as r]
            [et.tr.filters :as filters]
            [et.tr.ui.api :as api]
            [et.tr.ui.constants :refer [CATEGORY-TYPE-PERSON CATEGORY-TYPE-PLACE CATEGORY-TYPE-PROJECT CATEGORY-TYPE-GOAL]]))

(defonce *issues-page-state (r/atom {:expanded-issue nil
                                     :editing-issue nil
                                     :confirm-delete-issue nil
                                     :filter-search ""
                                     :importance-filter nil
                                     :sort-mode :recent
                                     :has-more? false
                                     :fetch-request-id 0}))

(def ^:private page-size 50)

(defn- filtered? [{:keys [search-term importance filter-people filter-places filter-projects filter-goals]}]
  (boolean
    (or (>= (count (str search-term)) 2)
        importance
        (seq filter-people)
        (seq filter-places)
        (seq filter-projects)
        (seq filter-goals))))

(defn- ids->names [ids collection]
  (let [id-set (set ids)
        matching (filter #(contains? id-set (:id %)) collection)]
    (mapv :name matching)))

(defn fetch-issues [app-state auth-headers opts]
  (let [request-id (:fetch-request-id (swap! *issues-page-state update :fetch-request-id inc))
        {:keys [search-term importance context strict filter-people filter-places filter-projects filter-goals sort-mode]} opts
        people-names (when (seq filter-people) (ids->names filter-people (:people @app-state)))
        place-names (when (seq filter-places) (ids->names filter-places (:places @app-state)))
        project-names (when (seq filter-projects) (ids->names filter-projects (:projects @app-state)))
        goal-names (when (seq filter-goals) (ids->names filter-goals (:goals @app-state)))
        paginate? (not (filtered? opts))
        offset (or (:offset opts) 0)
        append? (boolean (:append? opts))
        url (cond-> "/api/issues?paged=true&"
              paginate? (str "limit=" page-size "&offset=" offset "&")
              (seq search-term) (str "q=" (js/encodeURIComponent search-term) "&")
              importance (str "importance=" (name importance) "&")
              context (str "context=" (name context) "&")
              strict (str "strict=true&")
              (seq people-names) (str "people=" (js/encodeURIComponent (clojure.string/join "," people-names)) "&")
              (seq place-names) (str "places=" (js/encodeURIComponent (clojure.string/join "," place-names)) "&")
              (seq project-names) (str "projects=" (js/encodeURIComponent (clojure.string/join "," project-names)) "&")
              (seq goal-names) (str "goals=" (js/encodeURIComponent (clojure.string/join "," goal-names)) "&")
              sort-mode (str "sortMode=" (name sort-mode) "&"))]
    (GET url
      {:response-format :json
       :keywords? true
       :headers (auth-headers)
       :handler (fn [resp]
                  (when (= request-id (:fetch-request-id @*issues-page-state))
                    (let [items (:items resp)]
                      (swap! *issues-page-state assoc :has-more? (and paginate? (boolean (:has_more resp))))
                      (if append?
                        (swap! app-state update :issues #(into (vec %) items))
                        (swap! app-state assoc :issues items)))))
       :error-handler (fn [resp]
                        (when (= request-id (:fetch-request-id @*issues-page-state))
                          (swap! app-state assoc :error (get-in resp [:response :error] "Failed to load issues"))
                          (if append?
                            (swap! *issues-page-state assoc :has-more? true)
                            (do (swap! app-state assoc :issues [])
                                (swap! *issues-page-state assoc :has-more? false)))))})))

(defn add-issue [app-state auth-headers current-scope-fn title on-success fetch-issues-fn]
  (api/post-json "/api/issues"
    {:title title :scope (current-scope-fn)}
    (auth-headers)
    (fn [_]
      (fetch-issues-fn)
      (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to add issue")))))

(defn update-issue [app-state auth-headers issue-id title description tags expected-modified-at on-success on-error]
  (api/put-json (str "/api/issues/" issue-id)
    (cond-> {:title title}
      (some? description) (assoc :description description)
      (some? tags) (assoc :tags tags)
      expected-modified-at (assoc :expected-modified-at expected-modified-at))
    (auth-headers)
    (fn [result]
      (swap! app-state update :issues
             (fn [issues]
               (mapv #(if (= (:id %) issue-id) (merge % result) %) issues)))
      (when on-success (on-success)))
    (or on-error
        (fn [resp]
          (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update issue"))))))

(defn delete-issue [app-state auth-headers issue-id]
  (api/delete-simple (str "/api/issues/" issue-id)
    (auth-headers)
    (fn [_]
      (swap! app-state update :issues
             (fn [issues] (filterv #(not= (:id %) issue-id) issues)))
      (swap! *issues-page-state assoc :confirm-delete-issue nil))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to delete issue"))
      (swap! *issues-page-state assoc :confirm-delete-issue nil))))

(defn set-issue-scope [app-state auth-headers issue-id scope]
  (api/put-json (str "/api/issues/" issue-id "/scope")
    {:scope scope}
    (auth-headers)
    (fn [result]
      (swap! app-state update :issues
             (fn [issues]
               (let [mode (:work-private-mode @app-state)
                     strict? (:strict-mode @app-state)]
                 (->> issues
                      (mapv #(if (= (:id %) issue-id)
                               (assoc % :scope (:scope result))
                               %))
                      (filterv #(filters/matches-scope? % mode strict?)))))))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update scope")))))

(defn set-issue-importance [app-state auth-headers issue-id importance]
  (api/put-json (str "/api/issues/" issue-id "/importance")
    {:importance importance}
    (auth-headers)
    (fn [result]
      (swap! app-state update :issues
             (fn [issues]
               (mapv #(if (= (:id %) issue-id)
                        (assoc % :importance (:importance result))
                        %)
                     issues))))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update importance")))))

(defn categorize-issue [app-state auth-headers fetch-issues-fn issue-id category-type category-id]
  (api/post-json (str "/api/issues/" issue-id "/categorize")
    {:category-type category-type :category-id category-id}
    (auth-headers)
    (fn [_] (fetch-issues-fn))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to categorize issue")))))

(defn uncategorize-issue [app-state auth-headers fetch-issues-fn issue-id category-type category-id]
  (api/delete-json (str "/api/issues/" issue-id "/categorize")
    {:category-type category-type :category-id category-id}
    (auth-headers)
    (fn [_] (fetch-issues-fn))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to uncategorize issue")))))

(defn- categorize-issue-batch [auth-headers issue-id category-type ids]
  (doseq [id ids]
    (api/post-json (str "/api/issues/" issue-id "/categorize")
      {:category-type category-type :category-id id}
      (auth-headers)
      (fn [_]))))

(defn add-issue-with-categories [app-state auth-headers fetch-issues-fn current-scope-fn title categories on-success]
  (POST "/api/issues"
    {:params {:title title :scope (current-scope-fn)}
     :format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [issue]
                (let [issue-id (:id issue)
                      {:keys [people places projects goals]} categories]
                  (categorize-issue-batch auth-headers issue-id CATEGORY-TYPE-PERSON people)
                  (categorize-issue-batch auth-headers issue-id CATEGORY-TYPE-PLACE places)
                  (categorize-issue-batch auth-headers issue-id CATEGORY-TYPE-PROJECT projects)
                  (categorize-issue-batch auth-headers issue-id CATEGORY-TYPE-GOAL goals)
                  (js/setTimeout fetch-issues-fn 500)
                  (swap! app-state update :issues #(cons issue %))
                  (when on-success (on-success))))
     :error-handler (fn [resp]
                      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to add issue")))}))

(defn fetch-focused-issue
  "Load a single issue (with its :tasks) into the focused-view state slots so the
  Issues page can show that issue's task listing when the ◈ icon is clicked."
  [app-state auth-headers issue-id]
  (GET (str "/api/issues/" issue-id)
    {:response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [issue]
                (swap! app-state assoc
                       :issues-page/filter-issue {:id (:id issue) :title (:title issue)}
                       :issues-page/focused-issue issue))
     :error-handler (fn [resp]
                      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to load issue")))}))

(defn- categorize-task-batch [auth-headers task-id category-type ids]
  (doseq [id ids]
    (api/post-json (str "/api/tasks/" task-id "/categorize")
      {:category-type category-type :category-id id}
      (auth-headers)
      (fn [_]))))

(defn create-task-for-issue
  "Create a task (with the given title) belonging to the issue, then associate
  the currently-selected sidebar categories with it — mirroring how the Tasks
  page add form categorises a freshly-created task."
  [app-state auth-headers fetch-issues-fn categories issue-id title on-success]
  (POST (str "/api/issues/" issue-id "/create-task")
    {:params {:title title}
     :format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [task]
                (let [task-id (:id task)
                      {:keys [people places projects goals]} categories]
                  (categorize-task-batch auth-headers task-id CATEGORY-TYPE-PERSON people)
                  (categorize-task-batch auth-headers task-id CATEGORY-TYPE-PLACE places)
                  (categorize-task-batch auth-headers task-id CATEGORY-TYPE-PROJECT projects)
                  (categorize-task-batch auth-headers task-id CATEGORY-TYPE-GOAL goals)
                  (js/setTimeout fetch-issues-fn 500)
                  (when on-success (on-success))))
     :error-handler (fn [resp]
                      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to create task")))}))

(defn set-drag-issue [app-state issue-id]
  (swap! app-state assoc :drag-issue issue-id))

(defn set-drag-over-issue [app-state issue-id]
  (swap! app-state assoc :drag-over-issue issue-id))

(defn clear-issue-drag-state [app-state]
  (swap! app-state assoc :drag-issue nil :drag-over-issue nil))

(defn reorder-issue [app-state auth-headers fetch-issues-fn issue-id target-issue-id position]
  (api/post-json (str "/api/issues/" issue-id "/reorder")
    {:target-issue-id target-issue-id :position position}
    (auth-headers)
    (fn [_]
      (clear-issue-drag-state app-state)
      (fetch-issues-fn))
    (fn [resp]
      (clear-issue-drag-state app-state)
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to reorder issue")))))

(defn set-expanded-issue [id]
  (swap! *issues-page-state assoc :expanded-issue id :editing-issue nil)
  (when (nil? id)
    (js/setTimeout #(when-let [el (.getElementById js/document "issues-filter-search")]
                      (.focus el #js {:preventScroll true})) 0)))

(defn set-editing-issue [id]
  (swap! *issues-page-state assoc :editing-issue id))

(defn clear-editing-issue []
  (swap! *issues-page-state assoc :editing-issue nil))

(defn set-confirm-delete-issue [issue]
  (swap! *issues-page-state assoc :confirm-delete-issue issue))

(defn clear-confirm-delete-issue []
  (swap! *issues-page-state assoc :confirm-delete-issue nil))

(defn set-filter-search [fetch-issues-fn search-term]
  (swap! *issues-page-state assoc :filter-search search-term)
  (fetch-issues-fn))

(defn set-sort-mode [fetch-issues-fn mode]
  (swap! *issues-page-state assoc :sort-mode mode)
  (fetch-issues-fn))

(defn set-importance-filter [fetch-issues-fn level]
  (swap! *issues-page-state assoc :importance-filter level)
  (fetch-issues-fn))

(defn clear-all-issue-filters [fetch-issues-fn]
  (swap! *issues-page-state assoc :filter-search "" :importance-filter nil)
  (fetch-issues-fn))

(defn reset-issues-page-view-state! []
  (swap! *issues-page-state assoc
         :expanded-issue nil
         :editing-issue nil))
