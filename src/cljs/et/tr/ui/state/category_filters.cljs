(ns et.tr.ui.state.category-filters
  "The positive category filters, as query params.

  Every list endpoint takes the same per-Group filter params (people=,
  places=, workstreams=, projects=, goals=, assets=), and every fetch-opts map
  carries them as :filter-<group> sets of category ids. Both halves are
  generated from et.tr.ui.constants/category-groups here rather than written out
  once per Group in each of the nine list namespaces — which is how the four
  original groups came to be spelled out about forty times.

  The counterpart for the negative filters is et.tr.ui.state.exclusions."
  (:require [clojure.string :as str]
            [et.tr.ui.api :as api]
            [et.tr.ui.constants :as constants]))

(defn- ids->names [ids category-list]
  (->> category-list
       (filter #(contains? (set ids) (:id %)))
       (mapv :name)))

(defn filter-opt-key
  "The fetch-opts key holding one Group's selected ids, e.g. :filter-workstreams."
  [group-key]
  (keyword (str "filter-" (name group-key))))

(defn any-selected?
  "True when `opts` names a selection in any Group — what the list namespaces
  ask to decide whether a request is filtered at all."
  [opts]
  (boolean (some #(seq (get opts (filter-opt-key %))) constants/category-key-order)))

(defn query-params
  "The positive category filters as \"name=value\" strings, one per Group with a
  selection, for each URL builder to join into its own shape. Ids are resolved
  to names against the in-memory category lists, because the API filters by
  name."
  [app-state opts]
  (vec (for [group-key constants/category-key-order
             :let [ids (get opts (filter-opt-key group-key))
                   names (when (seq ids) (ids->names ids (get @app-state group-key)))]
             :when (seq names)]
         (str (name group-key) "=" (js/encodeURIComponent (str/join "," names))))))

(defn query-string
  "`query-params` joined with & and given a trailing separator when non-empty,
  so it drops into the `(cond-> url ...)` builders unchanged."
  [app-state opts]
  (let [params (query-params app-state opts)]
    (if (seq params) (str (str/join "&" params) "&") "")))

(defn apply-filter-categories!
  "Give a newly created Item every Category the caller is filtering by.

  `collection` is the API's URL segment for the Item's kind — \"tasks\",
  \"issues\", \"meets\", \"meeting-series\", \"recurring-tasks\",
  \"resources\" — and `categories` is the map `active-filter-categories`
  builds, one entry per Group key.

  Iterates `constants/category-groups` rather than naming the Groups, which is
  the whole point: Workstreams and Assets were added to that list in 2df2c3c
  and every hand-written enumeration of the Groups silently stopped being
  complete — a new Item under a Workstream filter simply lost it, in all seven
  of the add paths that had spelled the four original Groups out. A seventh
  Group is carried by this without a call site changing.

  A Group with nothing selected is `nil` here and `doseq` over nil is a no-op,
  so an unfiltered Group costs no request."
  [auth-headers collection id categories]
  (doseq [{:keys [key type]} constants/category-groups
          category-id (get categories key)]
    (api/post-json (str "/api/" collection "/" id "/categorize")
      {:category-type type :category-id category-id}
      (auth-headers)
      (fn [_]))))
