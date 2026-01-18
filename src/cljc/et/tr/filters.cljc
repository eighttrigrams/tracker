(ns et.tr.filters)

(def target-upcoming-tasks-count 10)

(defn apply-exclusion-filter [tasks excluded-places excluded-projects]
  (let [has-excluded-place? (fn [task]
                              (some #(contains? excluded-places (:id %)) (:places task)))
        has-excluded-project? (fn [task]
                                (some #(contains? excluded-projects (:id %)) (:projects task)))]
    (cond->> tasks
      (seq excluded-places) (remove has-excluded-place?)
      (seq excluded-projects) (remove has-excluded-project?))))
