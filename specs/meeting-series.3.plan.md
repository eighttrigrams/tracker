# Implementation Plan: Creating Meetings for Meeting Series

Based on the "Creating new Meetings for Meeting series" section in [specs/meeting-series.md](meeting-series.md).
Branch: `add-meeting-series`.

## Spec Summary

Each meeting series card shows a "Create next Meeting" button (always visible, bottom-left). The button is **grayed out** by default and only **enabled** when:

1. A meeting for today exists for this series AND no future meeting exists → creates the **next scheduled** meeting.
2. No meeting for today exists AND no future meeting exists AND today is a scheduled day → creates **today's** meeting.

Assumption: at most one meeting per day per series.

## Phase 1: Backend — Query for Series Meet Status

### `db/meeting_series.clj`

Add `meeting-series-next-action [ds user-id series-id schedule-days schedule-time]`:

Returns a map `{:action :create-next | :create-today | :none, :date "YYYY-MM-DD", :time "HH:MM"}` based on the rules:

- Query `meets` where `meeting_series_id = series-id` and `start_date >= today`, ordered by `start_date ASC`.
- If any future meet exists (start_date > today) → `:none`.
- If a meet exists for today but none in the future → `:create-next`. Compute the next scheduled date from `schedule_days` starting from tomorrow.
- If no meet for today and no future meet, and today's day-of-week is in `schedule_days` → `:create-today` with today's date.
- Otherwise → `:none`.

The time for the new meeting comes from `schedule_time` — either the shared time, or the per-day time for the target day.

Alternatively, this logic can be computed purely on the frontend from the series data + the list of meets. This avoids a new endpoint. **Preferred approach: compute on frontend**, since the series list endpoint already returns `schedule_days` and `schedule_time`, and we just need to know whether a meet exists for today / in the future for that series.

### Enriching series list with latest meet info

Add to `list-meeting-series`: for each series, include `:has_today_meet` (boolean) and `:has_future_meet` (boolean). This is done via subqueries in the list query, or as a post-processing step.

Simpler alternative: include `:latest_meet_date` — the max `start_date` of meets in this series. The frontend can derive everything from `latest_meet_date`, `schedule_days`, and today's date.

**Chosen approach**: Add a subquery to `list-meeting-series` that fetches `:has_today_meet` and `:has_future_meet` as boolean columns for each series.

### `db/meeting_series.clj`

Add `create-meeting-for-series [ds user-id series-id date time]`:

- Creates a new meet with `meeting_series_id = series-id`, `start_date = date`, `start_time = time`.
- Copies `title`, `scope`, `user_id` from the series.
- Copies all categories from `meeting_series_categories` to `meet_categories`.
- Returns the new meet.

## Phase 2: Backend — API

### `server/meeting_series_handler.clj`

Add `create-next-meeting-handler`:

`POST /api/meeting-series/:id/create-meeting`

Body: `{:date "YYYY-MM-DD" :time "HH:MM"}` — the frontend computes the date and time based on the schedule rules.

The handler:
1. Validates the date/time.
2. Checks there isn't already a meet for this series on that date.
3. Calls `create-meeting-for-series`.
4. Returns the new meet.

### `server.clj`

Add route: `(POST "/:id/create-meeting" [] meeting-series-handler/create-next-meeting-handler)`.

## Phase 3: Frontend State

### `state/meeting_series.cljs`

Add `create-meeting-for-series [app-state auth-headers fetch-fn series-id date time]`.

### `state.cljs`

Add wrapper `create-meeting-for-series [series-id date time]`.

Add helper `next-meeting-action [series]` — pure function that takes a series (with `:has_today_meet`, `:has_future_meet`, `:schedule_days`, `:schedule_time`) and today's date, returns `{:action :create-next | :create-today | :none, :date "...", :time "..."}`.

## Phase 4: Frontend View

### `views/meets.cljs` — Series item card

In the series item (always visible, not just when expanded), add a "Create next Meeting" button at the bottom-left:

- **Enabled** (blue) when `next-meeting-action` returns `:create-next` or `:create-today`.
- **Disabled** (grayed out) when `:none`.
- On click: calls `state/create-meeting-for-series` with the computed date and time.

### Translations

Add `:meets/create-next-meeting` key (en/de/pt).

## File Change Summary

| File | Change |
|---|---|
| `src/clj/et/tr/db/meeting_series.clj` | Add meet status subqueries to `list-meeting-series`, add `create-meeting-for-series` |
| `src/clj/et/tr/server/meeting_series_handler.clj` | Add `create-next-meeting-handler` |
| `src/clj/et/tr/server.clj` | Add route |
| `src/cljs/et/tr/ui/state/meeting_series.cljs` | Add `create-meeting-for-series` |
| `src/cljs/et/tr/ui/state.cljs` | Add wrapper + `next-meeting-action` helper |
| `src/cljs/et/tr/ui/views/meets.cljs` | Add button to series item |
| `resources/translations.edn` | Add translation keys |

## Implementation Order

1. Enrich `list-meeting-series` with `:has_today_meet` / `:has_future_meet`
2. Add `create-meeting-for-series` DB function
3. Add API handler + route
4. Frontend state functions
5. Frontend `next-meeting-action` logic
6. View: button in series card
7. Manual testing
