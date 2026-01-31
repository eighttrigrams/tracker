(ns et.tr.ui.state.tasks
  (:require [ajax.core :refer [GET POST]]
            [clojure.string :as str]
            [et.tr.ui.api :as api]))

(def ^:const CATEGORY-TYPE-PERSON "person")
(def ^:const CATEGORY-TYPE-PLACE "place")
(def ^:const CATEGORY-TYPE-PROJECT "project")
(def ^:const CATEGORY-TYPE-GOAL "goal")

(defn fetch-tasks
  ([app-state auth-headers calculate-best-horizon-fn]
   (fetch-tasks app-state auth-headers calculate-best-horizon-fn nil))
  ([app-state auth-headers calculate-best-horizon-fn {:keys [search-term]}]
   (let [sort-mode (name (:sort-mode @app-state))
         url (cond-> (str "/api/tasks?sort=" sort-mode)
               (seq search-term) (str "&q=" (js/encodeURIComponent search-term)))]
     (GET url
       {:response-format :json
        :keywords? true
        :headers (auth-headers)
        :handler (fn [tasks]
                   (swap! app-state assoc :tasks tasks)
                   (when (nil? (:upcoming-horizon @app-state))
                     (swap! app-state assoc :upcoming-horizon (calculate-best-horizon-fn tasks))))}))))

(defn- categorize-task-batch [auth-headers task-id category-type ids]
  (doseq [id ids]
    (api/post-json (str "/api/tasks/" task-id "/categorize")
      {:category-type category-type :category-id id}
      (auth-headers)
      (fn [_]))))

(defn add-task-with-categories [app-state auth-headers fetch-tasks-fn current-scope-fn title categories on-success]
  (POST "/api/tasks"
    {:params {:title title :scope (current-scope-fn)}
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
                  (js/setTimeout fetch-tasks-fn 500)
                  (swap! app-state update :tasks #(cons task %))
                  (when on-success (on-success))))
     :error-handler (fn [resp]
                      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to add task")))}))

(defn add-task [app-state auth-headers current-scope-fn has-active-filters-fn set-pending-new-task-fn title on-success]
  (if (str/blank? title)
    (swap! app-state assoc :error "Title is required")
    (if (has-active-filters-fn)
      (set-pending-new-task-fn title on-success)
      (POST "/api/tasks"
        {:params {:title title :scope (current-scope-fn)}
         :format :json
         :response-format :json
         :keywords? true
         :headers (auth-headers)
         :handler (fn [task]
                    (swap! app-state update :tasks #(cons task %))
                    (when on-success (on-success)))
         :error-handler (fn [resp]
                          (swap! app-state assoc :error (get-in resp [:response :error] "Failed to add task")))}))))

(defn update-task [app-state auth-headers task-id title description on-success]
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

(defn categorize-task [app-state auth-headers fetch-tasks-fn task-id category-type category-id]
  (api/post-json (str "/api/tasks/" task-id "/categorize")
    {:category-type category-type :category-id category-id}
    (auth-headers)
    (fn [_] (fetch-tasks-fn))))

(defn uncategorize-task [app-state auth-headers fetch-tasks-fn task-id category-type category-id]
  (api/delete-json (str "/api/tasks/" task-id "/categorize")
    {:category-type category-type :category-id category-id}
    (auth-headers)
    (fn [_] (fetch-tasks-fn))))

(defn set-task-due-date [app-state auth-headers task-id due-date]
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

(defn set-task-due-time [app-state auth-headers task-id due-time]
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

(defn set-confirm-delete-task [app-state task]
  (swap! app-state assoc :confirm-delete-task task))

(defn set-task-dropdown-open [app-state task-id]
  (swap! app-state assoc :task-dropdown-open task-id))

(defn clear-confirm-delete [app-state]
  (swap! app-state assoc :confirm-delete-task nil))

(defn delete-task [app-state auth-headers task-id]
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
      (clear-confirm-delete app-state))))

(defn set-task-done [app-state auth-headers fetch-tasks-fn task-id done?]
  (api/put-json (str "/api/tasks/" task-id "/done")
    {:done done?}
    (auth-headers)
    (fn [_]
      (swap! app-state assoc
             :tasks-page/expanded-task nil
             :today-page/expanded-task nil)
      (fetch-tasks-fn))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update task")))))

(defn set-task-scope [app-state auth-headers task-id scope]
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

(defn set-task-importance [app-state auth-headers task-id importance]
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

(defn set-task-urgency [app-state auth-headers task-id urgency]
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

(defn set-drag-task [app-state task-id]
  (swap! app-state assoc :drag-task task-id))

(defn set-drag-over-task [app-state task-id]
  (swap! app-state assoc :drag-over-task task-id))

(defn clear-drag-state [app-state]
  (swap! app-state assoc :drag-task nil :drag-over-task nil))

(defn reorder-task [app-state auth-headers fetch-tasks-fn task-id target-task-id position]
  (api/post-json (str "/api/tasks/" task-id "/reorder")
    {:target-task-id target-task-id :position position}
    (auth-headers)
    (fn [_]
      (clear-drag-state app-state)
      (fetch-tasks-fn))
    (fn [resp]
      (clear-drag-state app-state)
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to reorder")))))

(defn set-sort-mode [app-state fetch-tasks-fn mode]
  (swap! app-state assoc :sort-mode mode)
  (fetch-tasks-fn))

(defn task-done? [task]
  (= 1 (:done task)))
