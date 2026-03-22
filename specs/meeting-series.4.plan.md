# Implementation Plan: Today Page Behaviour for Meeting Series

Based on the "Today page behaviour" section in [specs/meeting-series.md](meeting-series.md).
Branch: `add-meeting-series`.

## Spec Summary

When a meeting belonging to a series appears in the Today section, and the series has no future meeting, the expanded card shows a "Create next Meeting" button (same as on the series card).

## Changes

### Backend

**`db.clj`**: Add `:meeting_series_id` to `meet-select-columns`.

**`db/meet.clj`**: In `list-meets`, for meets that have a `meeting_series_id`, add a boolean `:series_has_future_meet` via a correlated subquery. Also include `:schedule_days` and `:schedule_time` from the parent series via a LEFT JOIN or post-processing, so the frontend can compute the next date/time.

Simpler approach: after fetching meets, do a single batch query for all distinct `meeting_series_id` values to get `schedule_days`, `schedule_time`, and whether a future meet exists. Attach to each meet.

### Frontend

**`views/today.cljs`**: In `today-meet-expanded-details`, when the meet has `meeting_series_id` and `:series_has_future_meet` is false, show the "Create next Meeting" button. Use `state/next-meeting-action` logic adapted for this context — since we know today's meet exists and no future meet exists, the action is always `:create-next`.

### No new translations needed

Reuses `:meets/create-next-meeting`.

## File Change Summary

| File | Change |
|---|---|
| `src/clj/et/tr/db.clj` | Add `:meeting_series_id` to `meet-select-columns` |
| `src/clj/et/tr/db/meet.clj` | Enrich meets with series info for series members |
| `src/cljs/et/tr/ui/views/today.cljs` | Add "Create next Meeting" button in expanded today meet |

## Implementation Order

1. Add `meeting_series_id` to meet select columns
2. Enrich meets with series schedule info
3. Add button in today meet expanded view
