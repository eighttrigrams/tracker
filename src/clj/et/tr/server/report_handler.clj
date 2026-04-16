(ns et.tr.server.report-handler
  (:require [et.tr.server.common :as common]
            [et.tr.db :as db]
            [et.tr.db.task :as db.task]
            [et.tr.db.meet :as db.meet]
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

(defn reports-handler [req]
  (let [user-id (common/get-user-id req)
        ds (common/ensure-ds)
        context (get-in req [:params "context"])
        strict (= "true" (get-in req [:params "strict"]))
        people (common/parse-category-param (get-in req [:params "people"]))
        places (common/parse-category-param (get-in req [:params "places"]))
        projects (common/parse-category-param (get-in req [:params "projects"]))
        goals (common/parse-category-param (get-in req [:params "goals"]))
        categories (when (or people places projects goals)
                     {:people people :places places :projects projects :goals goals})
        shared-opts {:context context :strict strict :categories categories}
        tasks (db.task/list-tasks ds user-id :done shared-opts)
        meets (db.meet/list-meets ds user-id (assoc shared-opts :sort-mode :past))
        journal-entries (->> (db.journal-entry/list-journal-entries ds user-id
                               (assoc shared-opts :sort-mode "added"))
                             (filter :entry_date)
                             (annotate-schedule-types ds user-id))]
    {:status 200 :body {:tasks tasks :meets meets :journal_entries journal-entries}}))
