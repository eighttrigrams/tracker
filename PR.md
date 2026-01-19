# Feature: Sort by Due Time within Due Date

## Summary
In the Today view and Tasks view (Due date sort order), tasks are now sorted by due_time in addition to due_date. Tasks on the same day without a time appear first, followed by tasks with times in chronological order.

## Changes

### Backend (Clojure)
- `db.clj`: Updated `:due-date` sort ORDER BY to include `due_time IS NOT NULL, due_time ASC` (NULLs first)
- `db.clj`: Normalize empty string due_time to NULL in `set-task-due-time`

### Frontend (ClojureScript)
- `state.cljs`: Added `sort-by-date-and-time` helper function
- Updated `overdue-tasks`, `today-tasks`, `upcoming-tasks` to use new sorting
- Added comment explaining dual-sorting pattern (backend + frontend)

## Sort Order
1. By due_date ascending
2. Tasks without due_time first (NULL)
3. Tasks with due_time in chronological order

## Review Notes
- Architecture: Dual-sorting is intentional - backend sorts for Tasks page, frontend re-sorts for Today page after applying exclusion filters
- Security: No issues - hardcoded SQL, no injection risk
- Data Consistency: Fixed empty string handling to prevent sort/display mismatch
