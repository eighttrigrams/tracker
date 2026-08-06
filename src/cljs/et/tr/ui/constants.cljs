(ns et.tr.ui.constants)

(def ^:const CATEGORY-TYPE-PERSON "person")
(def ^:const CATEGORY-TYPE-PLACE "place")
(def ^:const CATEGORY-TYPE-WORKSTREAM "workstream")
(def ^:const CATEGORY-TYPE-PROJECT "project")
(def ^:const CATEGORY-TYPE-GOAL "goal")
(def ^:const CATEGORY-TYPE-ASSET "asset")

(def category-groups
  "The six Category Groups, in the order the owner asked for them: People,
  Places, Workstreams, Projects, Goals, Assets. The client-side mirror of
  et.tr.db/category-groups — same `:type` strings, same order.

  The UI word for this concept is Group. `:type` is the value the API stores in
  categories.category_type; `:key` is the plural keyword used for app-state
  collections, filter keys and the /api/<key> URL segment; `:tab` is the
  Categories-page tab; `:label` / `:singular` / `:search-add` are i18n keys."
  [{:type "person" :key :people :tab :cat-people
    :label :category/people :singular :category/person
    :search-add :category/search-or-add-person}
   {:type "place" :key :places :tab :cat-places
    :label :category/places :singular :category/place
    :search-add :category/search-or-add-place}
   {:type "workstream" :key :workstreams :tab :cat-workstreams
    :label :category/workstreams :singular :category/workstream
    :search-add :category/search-or-add-workstream}
   {:type "project" :key :projects :tab :cat-projects
    :label :category/projects :singular :category/project
    :search-add :category/search-or-add-project}
   {:type "goal" :key :goals :tab :cat-goals
    :label :category/goals :singular :category/goal
    :search-add :category/search-or-add-goal}
   {:type "asset" :key :assets :tab :cat-assets
    :label :category/assets :singular :category/asset
    :search-add :category/search-or-add-asset}])

(def category-key-order (mapv :key category-groups))
(def all-category-filters
  "The set of group keys, for the pages' collapsed-filter sets."
  (set category-key-order))
(def empty-category-searches
  "One empty search box per group, for the pages' category-search maps."
  (zipmap category-key-order (repeat "")))
(def category-type-order (mapv :type category-groups))
(def category-key->type (into {} (map (juxt :key :type)) category-groups))
(def category-type->key (into {} (map (juxt :type :key)) category-groups))
(def category-type->group (into {} (map (juxt :type identity)) category-groups))
(def category-key->group (into {} (map (juxt :key identity)) category-groups))
(def tab->category-key (into {} (map (juxt :tab :key)) category-groups))
(def category-key->endpoint
  (into {} (map (fn [{:keys [key]}] [key (str "/api/" (name key) "/")])) category-groups))

(def first-category-tab
  "The Categories tab to open when nothing else says which — the first Group in
  the registry rather than a named one, so removing or reordering Groups cannot
  leave this pointing at a tab that no longer exists."
  (:tab (first category-groups)))

(def category-tabs
  "Every tab that belongs to the Categories section: one per Group, plus the
  Rules page, which is not a Group but lives in the same tab row.

  One generated set, used both by the nav (which asks whether the Categories tab
  row is what should be on screen) and by state.ui's `supersection-key` (which
  asks whether leaving this tab should be remembered as the last Categories
  tab). It was written out by hand in the second of those and named four of the
  six Groups, so leaving Workstreams or Assets recorded nothing and the sidebar's
  Categories button took you back to whichever Group you had been on before."
  (conj (set (map :tab category-groups)) :cat-rules))

(def category-type-pairs
  "[[type plural-key] ...] in Group order — the shape the badge renderers walk."
  (mapv (juxt :type :key) category-groups))

(def sidebar-filter-configs
  "One sidebar filter group per Category Group, in Group order. Every page's
  sidebar (Tasks, Today, Issues, Meets, Resources, Reports) renders exactly this
  list, so a new Group appears in all of them at once."
  (mapv (fn [{:keys [type key label]}]
          {:filter-key key
           :title-key label
           :items-key key
           :filter-state-key (keyword "shared" (str "filter-" (name key)))
           :category-type type})
        category-groups))

(def category-shortcut-keys
  "Digit1..Digit6 -> Group, for the sidebar's keyboard shortcuts. Six groups
  still fit on the number row; a seventh would not, and would need a different
  scheme rather than a Digit7."
  (into {} (map-indexed (fn [i {:keys [key]}] [(str "Digit" (inc i)) key])) category-groups))

(def category-shortcut-numbers
  (into {} (map (fn [[k v]] [v (subs k 5)])) category-shortcut-keys))

(def category-key->edit-modal-type
  "Plural group key -> the :category-<type> keyword the edit modal is keyed by."
  (into {} (map (fn [{:keys [type key]}] [key (keyword (str "category-" type))])) category-groups))
