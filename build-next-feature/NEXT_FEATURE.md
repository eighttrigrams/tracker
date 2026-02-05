I think the drag and drop on the Manual sort order on the Tasks page
does exactly work like intended.

On the Today page, however, in the Urgent Matters section, when I drag a task
upon one further above, after the drop the task to be placed gets placed *under*
the target task, instead of replacing the target task and pushing everything else down,
like one reasonably expects it to happen.

---

As a sidequest, in `core.cljs`, we see
duplication in the defn `today-sidebar-filters`
To me that looks to very similar blocks which a function can be extracted from.

Also duplication in `sort-mode-toggle`, and especially `sidebar-filters`,
and `projects-places-tab` and `projects-goals-tab` looks suspiciously similar.