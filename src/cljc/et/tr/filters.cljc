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

(defn refocus-search-after-badge-click?
  "Whether a click on a card's category badge should put the cursor back in the
  page's search box. Only the positive filter does: :exclude and :bypass are
  different acts, and a click the gate refuses selected nothing, so there is
  nothing to come back from — which is the case worth having a test for, since
  the list does not move and a stray focus jump is easy to miss."
  [modifiers gate]
  (= :toggle (badge-gesture modifiers gate)))

(defn badge-consumes-click?
  "Whether the badge keeps a click to itself or lets it through to the row it
  sits in — on Today the badges render inside the clickable card header, so a
  click that falls through expands the card. It keeps a click a gesture runs
  on, and equally one it deliberately refuses: a shift-click while a positive
  filter is up, any click while a negative filter is. Those refusals are why
  the two pre-bypass gestures decide this and the bypass does not — the old
  badge attached its handler, and with it an unconditional stopPropagation,
  whenever the plain or the shift gesture was open. In the one state where
  neither is — a filter of the badge's own type selected, no negative filter —
  the click reached the row before Shift+Option existed, and still does."
  [modifiers gate]
  (boolean (or (badge-gesture modifiers gate)
               (badge-gesture {} gate)
               (badge-gesture {:shift? true} gate))))

(defn filter-by-name [items filter-text]
  (if (empty? filter-text)
    items
    (filter #(multi-prefix-matches? (str (:name %) " " (:tags %) " " (:badge_title %)) filter-text) items)))
