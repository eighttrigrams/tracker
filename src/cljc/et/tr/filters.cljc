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

(defn badge-label [category]
  (let [bt (:badge_title category)]
    (if (and bt (not (str/blank? bt))) bt (:name category))))

(defn badge-gesture
  "Which of a category badge's three filter gestures a click runs, or nil for
  none. Shift+Option (:bypass) is tested before shift alone, because shift alone
  is the negative filter and would otherwise swallow the pair. :bypass survives
  a filter of the badge's own type — the gesture's whole point is adding to an
  existing selection — but not the negative filter, which replaces the sidebar
  the positive filters live in."
  [{:keys [shift? alt?]} {:keys [negative-active? any-filters? type-filtered?]}]
  (cond
    (and shift? alt?) (when-not negative-active? :bypass)
    shift?            (when (or negative-active? (not any-filters?)) :exclude)
    :else             (when-not (or negative-active? type-filtered?) :toggle)))

(def ^:private badge-modifier-combos
  [{:shift? true :alt? true} {:shift? true} {}])

(defn badge-clickable?
  "Whether a badge offers any gesture at all. The modifier is unknown at render
  time, so the pointer cursor covers every open path and the handler picks the
  one the modifiers actually asked for."
  [gate]
  (boolean (some #(badge-gesture % gate) badge-modifier-combos)))

(defn filter-by-name [items filter-text]
  (if (empty? filter-text)
    items
    (filter #(multi-prefix-matches? (str (:name %) " " (:tags %) " " (:badge_title %)) filter-text) items)))

(defn apply-exclusion-filter [tasks excluded-places excluded-projects]
  (let [has-excluded-place? (fn [task]
                              (some #(contains? excluded-places (:id %)) (:places task)))
        has-excluded-project? (fn [task]
                                (some #(contains? excluded-projects (:id %)) (:projects task)))]
    (cond->> tasks
      (seq excluded-places) (remove has-excluded-place?)
      (seq excluded-projects) (remove has-excluded-project?))))
