(ns et.tr.ui.state.tasks
  (:require [ajax.core :refer [GET POST]]
            [clojure.string :as str]
            [et.tr.filters :as filters]
            [et.tr.ui.api :as api]
            [et.tr.ui.state.exclusions :as exclusions]
            [et.tr.ui.state.category-filters :as category-filters]
            [et.tr.ui.constants :refer [CATEGORY-TYPE-PERSON CATEGORY-TYPE-PLACE
                                        CATEGORY-TYPE-PROJECT CATEGORY-TYPE-GOAL]]))

(defn- ids-to-names [ids category-list]
  (let [id-set (set ids)]
    (->> category-list
         (filter #(contains? id-set (:id %)))
         (map :name))))

(defn- build-category-param [ids category-list]
  (when (seq ids)
    (let [names (ids-to-names ids category-list)]
      (when (seq names)
        (->> names
             (map js/encodeURIComponent)
             (clojure.string/join ","))))))

(defn fetch-tasks
  ([app-state auth-headers calculate-best-horizon-fn]
   (fetch-tasks app-state auth-headers calculate-best-horizon-fn nil))
  ([app-state auth-headers calculate-best-horizon-fn {:keys [search-term importance context strict
                                                              recurring-task-id issue-id]
                                                       :as opts}]
   (let [sort-mode (name (:sort-mode @app-state))
         category-params (category-filters/query-params app-state opts)
         excluded-params (exclusions/query-params app-state)
         url (cond-> (str "/api/tasks?sort=" sort-mode)
               (seq search-term) (str "&q=" (js/encodeURIComponent search-term))
               importance (str "&importance=" (name importance))
               context (str "&context=" (name context))
               strict (str "&strict=true")
               (seq category-params) (str "&" (str/join "&" category-params))
               (seq excluded-params) (str "&" (str/join "&" excluded-params))
               recurring-task-id (str "&recurring-task-id=" recurring-task-id)
               issue-id (str "&issue=" issue-id))]
     (GET url
       {:response-format :json
        :keywords? true
        :headers (auth-headers)
        :handler (fn [tasks]
                   (swap! app-state assoc :tasks tasks)
                   (when (nil? (:upcoming-horizon @app-state))
                     (swap! app-state assoc :upcoming-horizon (calculate-best-horizon-fn app-state))))}))))

(defn- categorize-task-batch [auth-headers task-id category-type ids]
  (doseq [id ids]
    (api/post-json (str "/api/tasks/" task-id "/categorize")
      {:category-type category-type :category-id id}
      (auth-headers)
      (fn [_]))))

(defn add-task-with-categories [app-state auth-headers fetch-tasks-fn current-scope-fn current-importance-fn title categories on-success]
  (POST "/api/tasks"
    {:params {:title title :scope (current-scope-fn) :importance (current-importance-fn)}
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

(defn add-task [app-state auth-headers current-scope-fn current-importance-fn has-active-filters-fn add-with-categories-fn title on-success]
  (if (str/blank? title)
    (swap! app-state assoc :error "Title is required")
    (if (has-active-filters-fn)
      (add-with-categories-fn title on-success)
      (POST "/api/tasks"
        {:params {:title title :scope (current-scope-fn) :importance (current-importance-fn)}
         :format :json
         :response-format :json
         :keywords? true
         :headers (auth-headers)
         :handler (fn [task]
                    (swap! app-state update :tasks #(cons task %))
                    (when on-success (on-success)))
         :error-handler (fn [resp]
                          (swap! app-state assoc :error (get-in resp [:response :error] "Failed to add task")))}))))

(defn update-task [app-state auth-headers task-id title description tags expected-modified-at on-success on-error]
  (api/put-json (str "/api/tasks/" task-id)
    (cond-> {:title title :description description :tags tags}
      expected-modified-at (assoc :expected-modified-at expected-modified-at))
    (auth-headers)
    (fn [updated-task]
      (let [merge-fn (fn [tasks]
                       (mapv #(if (= (:id %) task-id) (merge % updated-task) %) tasks))]
        (swap! app-state (fn [s]
                           (-> s
                               (update :tasks merge-fn)
                               (update-in [:reports-data :tasks] merge-fn)))))
      (when on-success (on-success)))
    (or on-error
        (fn [resp]
          (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update task"))))))

(defn categorize-task [_app-state auth-headers fetch-tasks-fn task-id category-type category-id]
  (api/post-json (str "/api/tasks/" task-id "/categorize")
    {:category-type category-type :category-id category-id}
    (auth-headers)
    (fn [_] (fetch-tasks-fn))))

(defn uncategorize-task [_app-state auth-headers fetch-tasks-fn task-id category-type category-id]
  (api/delete-json (str "/api/tasks/" task-id "/categorize")
    {:category-type category-type :category-id category-id}
    (auth-headers)
    (fn [_] (fetch-tasks-fn))))

(defn set-task-due-date [app-state auth-headers task-id due-date on-success on-error]
  (api/put-json (str "/api/tasks/" task-id "/due-date")
    {:due-date due-date}
    (auth-headers)
    (fn [result]
      (swap! app-state update :tasks
             (fn [tasks]
               (mapv #(if (= (:id %) task-id)
                        (merge % (select-keys result [:due_date :due_time :today :lined_up_for :maybe :modified_at]))
                        %)
                     tasks)))
      (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to set due date"))
      (when on-error (on-error)))))

(defn set-task-due-time [app-state auth-headers task-id due-time on-success on-error]
  (api/put-json (str "/api/tasks/" task-id "/due-time")
    {:due-time due-time}
    (auth-headers)
    (fn [result]
      (swap! app-state update :tasks
             (fn [tasks]
               (mapv #(if (= (:id %) task-id)
                        (assoc % :due_date (:due_date result) :due_time (:due_time result) :modified_at (:modified_at result))
                        %)
                     tasks)))
      (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to set due time"))
      (when on-error (on-error)))))

(defn set-confirm-delete-task [app-state task]
  (swap! app-state assoc :confirm-delete-task task))

(defn set-task-dropdown-open [app-state task-id]
  (swap! app-state assoc :task-dropdown-open
         (when (not= (:task-dropdown-open @app-state) task-id) task-id)))

(defn clear-confirm-delete [app-state]
  (swap! app-state assoc :confirm-delete-task nil))

;; The server drops the marker along with the task, so the atom key only has to
;; catch up locally — with no refetch of the singleton.
(defn- forget-working-on [state task-id]
  (cond-> state
    (= task-id (:working-on-task-id state)) (assoc :working-on-task-id nil)))

(defn delete-task [app-state auth-headers task-id]
  (api/delete-simple (str "/api/tasks/" task-id)
    (auth-headers)
    (fn [_]
      (let [remove-fn (fn [tasks] (filterv #(not= (:id %) task-id) tasks))]
        (swap! app-state
               (fn [state]
                 (-> state
                     (update :tasks remove-fn)
                     (update-in [:reports-data :tasks] remove-fn)
                     (forget-working-on task-id)
                     (assoc :tasks-page/expanded-task nil
                            :today-page/expanded-task nil
                            :confirm-delete-task nil))))))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to delete task"))
      (clear-confirm-delete app-state))))

(defn set-task-done [app-state auth-headers fetch-tasks-fn task-id done?]
  (api/put-json (str "/api/tasks/" task-id "/done")
    {:done done?}
    (auth-headers)
    (fn [_]
      (swap! app-state
             (fn [state]
               (cond-> (assoc state
                              :tasks-page/expanded-task nil
                              :today-page/expanded-task nil)
                 done? (forget-working-on task-id))))
      (fetch-tasks-fn))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update task")))))

(defn set-task-scope [app-state auth-headers task-id scope]
  (api/put-json (str "/api/tasks/" task-id "/scope")
    {:scope scope}
    (auth-headers)
    (fn [result]
      (let [mode (:work-private-mode @app-state)
            strict? (:strict-mode @app-state)
            update-and-filter (fn [coll]
                                (->> coll
                                     (mapv #(if (= (:id %) task-id)
                                              (assoc % :scope (:scope result) :modified_at (:modified_at result))
                                              %))
                                     (filterv #(filters/matches-scope? % mode strict?))))]
        (swap! app-state update :tasks update-and-filter)
        (swap! app-state update-in [:reports-data :tasks] update-and-filter)))
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

(defn- merge-day-membership [app-state fetch-tasks-fn task-id result]
  (let [found? (some #(= (:id %) task-id) (:tasks @app-state))]
    (if found?
      (swap! app-state update :tasks
             (fn [tasks]
               (mapv #(if (= (:id %) task-id)
                        (merge % (select-keys result [:today :lined_up_for :maybe :modified_at]))
                        %)
                     tasks)))
      (fetch-tasks-fn))))

;; The optional `on-success` is for what has to follow the membership write
;; rather than accompany it — re-reading the day lists, whose order the backend
;; owns and whose contents this call has just changed.
(defn set-task-today
  ([app-state auth-headers fetch-tasks-fn task-id today?]
   (set-task-today app-state auth-headers fetch-tasks-fn task-id today? nil))
  ([app-state auth-headers fetch-tasks-fn task-id today? on-success]
   (api/put-json (str "/api/tasks/" task-id "/today")
     {:today today?}
     (auth-headers)
     (fn [result]
       (merge-day-membership app-state fetch-tasks-fn task-id result)
       (when on-success (on-success)))
     (fn [resp]
       (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update today flag"))))))

(defn set-task-lined-up-for
  ([app-state auth-headers fetch-tasks-fn task-id date]
   (set-task-lined-up-for app-state auth-headers fetch-tasks-fn task-id date nil))
  ([app-state auth-headers fetch-tasks-fn task-id date on-success]
   (api/put-json (str "/api/tasks/" task-id "/lined-up-for")
     {:lined_up_for date}
     (auth-headers)
     (fn [result]
       (merge-day-membership app-state fetch-tasks-fn task-id result)
       (when on-success (on-success)))
     (fn [resp]
       (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update lined-up-for"))))))

(defn set-task-maybe [app-state auth-headers task-id maybe?]
  (api/put-json (str "/api/tasks/" task-id "/maybe")
    {:maybe maybe?}
    (auth-headers)
    (fn [result]
      (swap! app-state update :tasks
             (fn [tasks]
               (mapv #(if (= (:id %) task-id)
                        (merge % (select-keys result [:maybe :modified_at]))
                        %)
                     tasks))))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update maybe flag")))))

;; The marker expires with the day, and nothing in the SPA re-renders at
;; midnight, so a page left open overnight has to be told the day turned.
(defonce ^:private *midnight-timer (atom nil))

(defn- ms-to-next-midnight []
  (let [now (js/Date.)
        next-midnight (js/Date. (.getFullYear now) (.getMonth now) (inc (.getDate now)))]
    (- (.getTime next-midnight) (.getTime now))))

(defn- arm-midnight-timer! [app-state on-midnight]
  (when-let [id @*midnight-timer]
    (js/clearTimeout id))
  (reset! *midnight-timer
          (js/setTimeout (fn []
                           (reset! *midnight-timer nil)
                           (swap! app-state assoc :working-on-task-id nil)
                           (on-midnight))
                         (ms-to-next-midnight))))

(defn fetch-working-on [app-state auth-headers]
  (api/fetch-json "/api/working-on"
    (auth-headers)
    (fn [marker]
      (swap! app-state assoc :working-on-task-id (:task-id marker))
      (arm-midnight-timer! app-state #(fetch-working-on app-state auth-headers)))))

(defn set-working-on [app-state auth-headers task-id on?]
  (api/put-json (str "/api/tasks/" task-id "/work-on")
    {:work-on on?}
    (auth-headers)
    ;; No task refetch: the indicator is derived from the singleton, so the
    ;; task that just lost the marker drops its dot on the same re-render.
    (fn [marker]
      (swap! app-state assoc :working-on-task-id (:task-id marker)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update working-on")))))

(defn set-task-done-at [app-state auth-headers task-id done-date]
  (api/put-json (str "/api/tasks/" task-id "/done-at")
    {:done-date done-date}
    (auth-headers)
    (fn [result]
      (let [merge-fn (fn [tasks]
                       (mapv #(if (= (:id %) task-id)
                                (merge % (select-keys result [:done_at :modified_at]))
                                %)
                             tasks))]
        (swap! app-state (fn [s]
                           (-> s
                               (update :tasks merge-fn)
                               (update-in [:reports-data :tasks] merge-fn))))))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to change done date")))))

(defn set-task-reminder [app-state auth-headers task-id reminder-date]
  (api/put-json (str "/api/tasks/" task-id "/reminder")
    {:reminder-date reminder-date}
    (auth-headers)
    (fn [result]
      (swap! app-state update :tasks
             (fn [tasks]
               (mapv #(if (= (:id %) task-id)
                        (merge % (select-keys result [:reminder :reminder_date :modified_at]))
                        %)
                     tasks))))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to set reminder")))))

(defn acknowledge-task-reminder [app-state auth-headers task-id]
  (api/put-json (str "/api/tasks/" task-id "/acknowledge-reminder")
    {}
    (auth-headers)
    (fn [result]
      (swap! app-state update :tasks
             (fn [tasks]
               (mapv #(if (= (:id %) task-id)
                        (merge % (select-keys result [:reminder :reminder_date :modified_at]))
                        %)
                     tasks))))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to acknowledge reminder")))))

(defn set-drag-task [app-state task-id]
  (swap! app-state assoc :drag-task task-id :drag-task-source nil))

(defn set-drag-over-task [app-state task-id]
  (swap! app-state assoc :drag-over-task task-id))

(defn set-drag-over-urgency-section [app-state section]
  (swap! app-state assoc :drag-over-urgency-section section))

(defn clear-drag-state [app-state]
  (swap! app-state assoc :drag-task nil :drag-over-task nil :drag-over-urgency-section nil :drag-task-source nil))

(defn- post-reorder [app-state auth-headers fetch-tasks-fn endpoint task-id params]
  (api/post-json (str "/api/tasks/" task-id endpoint)
    params
    (auth-headers)
    (fn [_]
      (clear-drag-state app-state)
      (fetch-tasks-fn))
    (fn [resp]
      (clear-drag-state app-state)
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to reorder")))))

(defn reorder-task [app-state auth-headers fetch-tasks-fn task-id target-task-id position]
  (post-reorder app-state auth-headers fetch-tasks-fn "/reorder" task-id
                {:target-task-id target-task-id :position position}))

(defn reorder-task-in-urgent [app-state auth-headers fetch-tasks-fn task-id urgency target-task-id position]
  (post-reorder app-state auth-headers fetch-tasks-fn "/reorder-urgent" task-id
                {:urgency urgency :target-task-id target-task-id :position position}))

;; The refetch runs on failure too: the day list was spliced optimistically, and
;; the backend's order is what puts a refused drop back where it came from.
(defn reorder-task-in-day [app-state auth-headers fetch-tasks-fn task-id date target position]
  (api/post-json (str "/api/tasks/" task-id "/reorder-today")
    {:date date
     :target-type (name (:item-type target))
     :target-id (:id target)
     :position position}
    (auth-headers)
    (fn [_]
      (clear-drag-state app-state)
      (fetch-tasks-fn))
    (fn [resp]
      (clear-drag-state app-state)
      (fetch-tasks-fn)
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to reorder")))))

(defn set-sort-mode [app-state fetch-tasks-fn mode]
  (swap! app-state assoc
         :sort-mode mode
         :tasks-page/last-sort-mode mode)
  (fetch-tasks-fn))

(defn task-done? [task]
  (= 1 (:done task)))
