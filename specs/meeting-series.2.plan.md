# Implementation Plan: Meeting Series Scheduling

Based on the "Scheduling" section in [specs/meeting-series.md](meeting-series.md).
Branch: `add-meeting-series`.

## Phase 1: Database Migration

### 027-add-meeting-series-schedule

Add two columns to `meeting_series`:

```sql
ALTER TABLE meeting_series ADD COLUMN schedule_days TEXT NOT NULL DEFAULT '';
ALTER TABLE meeting_series ADD COLUMN schedule_time TEXT DEFAULT NULL;
```

- `schedule_days`: comma-separated day-of-week numbers (1=Monday … 7=Sunday), e.g. `"1,3,5"` for Mon/Wed/Fri. Empty string means no schedule.
- `schedule_time`: HH:MM format (24h), e.g. `"09:00"`. NULL means not set.

## Phase 2: Backend

### `db.clj`

Add `:schedule_days` and `:schedule_time` to `meeting-series-select-columns`.

### `db/meeting_series.clj`

Add `set-meeting-series-schedule [ds user-id series-id schedule-days schedule-time]` — updates both columns + `modified_at`.

### `server/meeting_series_handler.clj`

Add `set-meeting-series-schedule-handler` — PUT `/api/meeting-series/:id/schedule` with body `{:schedule-days "1,3" :schedule-time "09:00"}`. Validates time format.

### `server.clj`

Add route: `(PUT "/:id/schedule" [] meeting-series-handler/set-meeting-series-schedule-handler)`.

## Phase 3: Frontend State

### `state/meeting_series.cljs`

Add `set-meeting-series-schedule [app-state auth-headers series-id schedule-days schedule-time on-success]`.

### `state.cljs`

Add wrapper `set-meeting-series-schedule [series-id schedule-days schedule-time on-success]`.

## Phase 4: Frontend View — Edit Modal

### `modals.cljs`

For `:meeting-series` type only:
- Add a third tab "Scheduling" in the edit-modal-tabs row.
- The scheduling tab renders:
  - A row of 7 day-of-week toggle buttons (Mon–Sun), each toggleable on/off.
  - A time input (HH:MM).
- The schedule state (`schedule-days`, `schedule-time`) is initialized from the entity and stored in local atoms within the modal.
- The existing Save button saves both the entity fields and the schedule in one go.

### Translations

Add `:modal/tab-scheduling`, `:scheduling/time` keys (en/de/pt).

## File Change Summary

| File | Change |
|---|---|
| `resources/migrations/027-add-meeting-series-schedule.edn` | New |
| `src/clj/et/tr/db.clj` | Add columns to `meeting-series-select-columns` |
| `src/clj/et/tr/db/meeting_series.clj` | Add `set-meeting-series-schedule` |
| `src/clj/et/tr/server/meeting_series_handler.clj` | Add schedule handler |
| `src/clj/et/tr/server.clj` | Add schedule route |
| `src/cljs/et/tr/ui/state/meeting_series.cljs` | Add `set-meeting-series-schedule` |
| `src/cljs/et/tr/ui/state.cljs` | Add wrapper |
| `src/cljs/et/tr/ui/modals.cljs` | Add scheduling tab for meeting-series |
| `resources/translations.edn` | Add scheduling translation keys |
