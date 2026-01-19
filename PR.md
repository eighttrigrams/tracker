# Feature: Sort by Due Time within Due Date

## Summary
In the Today view and Tasks view (Due date sort order), tasks are now sorted by due_time in addition to due_date. Tasks on the same day without a time appear first, followed by tasks with times in chronological order.

## Changes

### Backend (Clojure)
- `db.clj`: Updated `:due-date` sort ORDER BY to include `due_time IS NOT NULL, due_time ASC` (NULLs first)

### Frontend (ClojureScript)
- `state.cljs`: Added `sort-by-date-and-time` helper function
- Updated `overdue-tasks`, `today-tasks`, `upcoming-tasks` to use new sorting

## Sort Order
1. By due_date ascending
2. Tasks without due_time first (NULL)
3. Tasks with due_time in chronological order
