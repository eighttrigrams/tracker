(ns et.tr.filters
  (:require [clojure.string :as str]))

(def target-upcoming-tasks-count 10)

(defn matches-scope? [task mode strict?]
  (let [task-scope (or (:scope task) "both")]
    (if strict?
      (= task-scope (name mode))
      (case mode
        :private (contains? #{"private" "both"} task-scope)
        :work (contains? #{"work" "both"} task-scope)
        :both true
        true))))

(defn multi-prefix-matches? [text search-term]
  (if (str/blank? search-term)
    true
    (let [text-words (str/split (str/lower-case text) #"\s+")
          search-prefixes (str/split (str/lower-case (str/trim search-term)) #"\s+")]
      (every? (fn [prefix]
                (some #(str/starts-with? % prefix) text-words))
              search-prefixes))))

(defn filter-by-name [items filter-text]
  (if (empty? filter-text)
    items
    (filter #(multi-prefix-matches? (str (:name %) " " (:tags %)) filter-text) items)))

(defn apply-exclusion-filter [tasks excluded-places excluded-projects]
  (let [has-excluded-place? (fn [task]
                              (some #(contains? excluded-places (:id %)) (:places task)))
        has-excluded-project? (fn [task]
                                (some #(contains? excluded-projects (:id %)) (:projects task)))]
    (cond->> tasks
      (seq excluded-places) (remove has-excluded-place?)
      (seq excluded-projects) (remove has-excluded-project?))))
