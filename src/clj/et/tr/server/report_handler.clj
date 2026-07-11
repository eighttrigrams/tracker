(ns et.tr.server.report-handler
  (:require [et.tr.server.common :as common]
            [et.tr.server.week-window :as week-window]
            [et.tr.db :as db]
            [et.tr.db.task :as db.task]
            [et.tr.db.meet :as db.meet]
            [et.tr.db.issue :as db.issue]
            [et.tr.db.journal-entry :as db.journal-entry]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]))

(defn- annotate-schedule-types [ds user-id entries]
  (if (empty? entries)
    entries
    (let [journal-ids (->> entries (keep :journal_id) distinct vec)
          schedule-map (when (seq journal-ids)
                         (into {}
                           (map (juxt :id :schedule_type)
                                (jdbc/execute! (db/get-conn ds)
                                  (sql/format {:select [:id :schedule_type]
                                               :from [:journals]
                                               :where [:and (db/user-id-where-clause user-id)
                                                            [:in :id journal-ids]]})
                                  db/jdbc-opts))))]
      (mapv #(assoc % :schedule_type (get schedule-map (:journal_id %))) entries))))

(defn reports-handler
  "GET /api/reports — list resolved issues, completed/past tasks, meets, and
  journal entries for reporting. Query params: context (filter by context
  title), strict (\"true\" for strict context match), items (\"all\" |
  \"issues-tasks-meets\" | \"issues-tasks\" | \"journals\"), and
  people/places/projects/goals (comma-separated category filters). Returns
  {:issues :tasks :meets :journal_entries}, with journal entries annotated by
  :schedule_type. Resolved issues are windowed on resolved_at, tasks on
  done_at, meets/entries on their dates."
  [req]
  (let [user-id (common/get-user-id req)
        ds (common/ensure-ds)
        context (get-in req [:params "context"])
        strict (= "true" (get-in req [:params "strict"]))
        items (or (get-in req [:params "items"]) "all")
        include-issues? (contains? #{"all" "issues-tasks-meets" "issues-tasks"} items)
        include-tasks? (contains? #{"all" "issues-tasks-meets" "issues-tasks"} items)
        include-meets? (contains? #{"all" "issues-tasks-meets"} items)
        include-journals? (contains? #{"all" "journals"} items)
        people (common/parse-category-param (get-in req [:params "people"]))
        places (common/parse-category-param (get-in req [:params "places"]))
        projects (common/parse-category-param (get-in req [:params "projects"]))
        goals (common/parse-category-param (get-in req [:params "goals"]))
        categories (when (or people places projects goals)
                     {:people people :places places :projects projects :goals goals})
        week-offset-param (get-in req [:params "weekOffset"])
        week-limit-param (get-in req [:params "weekLimit"])
        window (week-window/week-window (week-window/parse-week-param week-offset-param 0)
                                        (week-window/parse-week-param week-limit-param 1)
                                        :backward)
        shared-opts {:context context :strict strict :categories categories}
        window-opts (merge shared-opts (when window {:date-from (:date-from window)
                                                     :date-to (:date-to window)}))
        issues (if include-issues?
                 (db.issue/list-issues ds user-id (assoc window-opts :sort-mode "resolved"))
                 [])
        tasks (if include-tasks?
                (db.task/list-tasks ds user-id :done window-opts)
                [])
        meets (if include-meets?
                (db.meet/list-meets ds user-id (assoc window-opts :sort-mode :past))
                [])
        journal-entries (if include-journals?
                          (->> (db.journal-entry/list-journal-entries ds user-id
                                 (assoc window-opts :sort-mode "added"))
                               (filter :entry_date)
                               (annotate-schedule-types ds user-id))
                          [])
        older-opts (assoc shared-opts :date-to (:date-from window) :limit 1)
        has-more (boolean
                   (and window
                        (or (and include-issues?
                                 (seq (db.issue/list-issues ds user-id (assoc older-opts :sort-mode "resolved"))))
                            (and include-tasks?
                                 (seq (db.task/list-tasks ds user-id :done older-opts)))
                            (and include-meets?
                                 (seq (db.meet/list-meets ds user-id (assoc older-opts :sort-mode :past))))
                            (and include-journals?
                                 (seq (->> (db.journal-entry/list-journal-entries ds user-id
                                             (assoc older-opts :sort-mode "added"))
                                           (filter :entry_date)))))))]
    {:status 200 :body {:issues issues :tasks tasks :meets meets :journal_entries journal-entries :has_more has-more}}))
