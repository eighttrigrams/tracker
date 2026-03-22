# Implementation Plan: Meeting Series

Branch: `implement-meeting-series-1`.

## Overview

Meeting Series are a new entity type, separate from Meetings, with a 1:n relationship to Meetings.
They share the Meetings page via a "Series" toggle button. When toggled on, only Meeting Series
are shown (not individual Meetings). Meeting Series support search, add, category filters, and
importance -- but no urgency, no date, no time.

## Layers (bottom-up)

### 1. Database Migrations

**Migration 026-add-meet-series.edn** -- Core table:
```sql
CREATE TABLE meet_series (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  title TEXT NOT NULL,
  description TEXT DEFAULT '',
  tags TEXT DEFAULT '',
  created_at DATETIME DEFAULT (datetime('now')),
  modified_at DATETIME DEFAULT (datetime('now')),
  sort_order REAL NOT NULL DEFAULT 0,
  scope TEXT NOT NULL DEFAULT 'both' CHECK (scope IN ('private', 'both', 'work')),
  importance TEXT NOT NULL DEFAULT 'normal' CHECK (importance IN ('normal', 'important', 'critical')),
  user_id INTEGER REFERENCES users(id) ON DELETE CASCADE
)
```
Index: `idx_meet_series_user_scope(user_id, scope)`

**Migration 027-add-meet-series-categories.edn** -- Join table:
```sql
CREATE TABLE meet_series_categories (
  meet_series_id INTEGER NOT NULL,
  category_type TEXT NOT NULL,
  category_id INTEGER NOT NULL,
  PRIMARY KEY (meet_series_id, category_type, category_id),
  FOREIGN KEY (meet_series_id) REFERENCES meet_series(id) ON DELETE CASCADE
)
```

No date/time columns needed (spec says "Meeting Series have no time or date by themselves").

### 2. Backend DB Layer (`db.clj`)

Add functions mirroring the meets pattern:

- `meet-series-select-columns` -- like `meet-select-columns` but without `:start_date` `:start_time`
- `add-meet-series [ds user-id title scope]`
- `list-meet-series [ds user-id opts]` -- opts: `:search-term`, `:importance`, `:context`, `:strict`, `:categories`, `:sort-mode`
  - Sort by `sort_order` (no date-based sorting since series have no dates)
- `update-meet-series [ds user-id series-id fields]`
- `delete-meet-series [ds user-id series-id]`
- `set-meet-series-field [ds user-id series-id field value]`
- `set-meet-series-scope`, `set-meet-series-importance` (via `set-meet-series-field`)
- `categorize-meet-series`, `uncategorize-meet-series`

Reuse existing helpers: `build-search-clause`, `build-importance-clause`, `build-scope-clause`, `build-category-subquery`, etc. -- these are parameterized by table name already or can be reused directly.

### 3. Backend API Routes (`server.clj`)

```
GET    /api/meet-series              -> list-meet-series-handler
POST   /api/meet-series              -> add-meet-series-handler
PUT    /api/meet-series/:id          -> update-meet-series-handler
DELETE /api/meet-series/:id          -> delete-meet-series-handler
POST   /api/meet-series/:id/categorize   -> categorize-meet-series-handler
DELETE /api/meet-series/:id/categorize   -> uncategorize-meet-series-handler
PUT    /api/meet-series/:id/scope        -> set-meet-series-scope-handler
PUT    /api/meet-series/:id/importance   -> set-meet-series-importance-handler
```

No start-date/start-time endpoints (series have no dates).

### 4. Frontend State (`state/meet_series.cljs`)

New state module, following `state/meets.cljs` pattern:

- `*meet-series-page-state` atom: `{:expanded nil, :filter-search "", :importance-filter nil, :fetch-request-id 0}`
  - No `:sort-mode` (no date-based sorting for series)
- `fetch-meet-series`, `add-meet-series`, `update-meet-series`, `delete-meet-series`
- `set-meet-series-scope`, `set-meet-series-importance`
- `categorize-meet-series`, `uncategorize-meet-series`
- `set-expanded`, `set-filter-search`, `set-importance-filter`

### 5. Frontend Global State (`state.cljs`)

- Add `:meet-series []` to `*app-state` and `initial-collection-state`
- Add `:meets-page/series-mode false` to `*app-state` (the toggle state)
- Add delegation functions: `fetch-meet-series`, `add-meet-series`, `toggle-meets-series-mode`, etc.
- When series mode is on, `fetch-meet-series` is called instead of `fetch-meets`
- Reuse `:meets-page/filter-goals`, `:shared/filter-*`, `:meets-page/collapsed-filters`, `:meets-page/category-search` -- the sidebar filters are shared between both views

### 6. Frontend View (`views/meets.cljs`)

Modify the existing `meets-tab` to support both modes:

**Header row changes:**
- Add a "Series" toggle button as the rightmost group (after sort-mode-toggle)
- When series mode is active, hide the sort-mode-toggle (no date sorting for series)

**Series mode rendering:**
- When `:meets-page/series-mode` is true:
  - `search-add-form` calls `add-meet-series` / `set-meet-series-filter-search` instead
  - List renders `meet-series` items instead of `meets`
  - Series items look like meet items but without date/time pickers
  - Expanded view has: description, category selectors, scope selector, importance selector, delete button -- no date/time pickers

**Reuse strategy:**
- The sidebar filters component is shared as-is
- The importance filter toggle is shared as-is
- Category selector, filter section components are reused unchanged
- Item rendering (header, expanded view, categories readonly) needs series-specific variants without date/time

### 7. Backend Tests

Add `test/clj/et/tr/meet_series_db_test.clj`:
- `add-meet-series-test`
- `list-meet-series-with-filters-test`
- `categorize-meet-series-test`
- `delete-meet-series-test`

## Execution Order

1. Migrations (026, 027)
2. DB functions in `db.clj`
3. DB tests
4. API routes in `server.clj`
5. Frontend state module `state/meet_series.cljs`
6. Frontend global state wiring in `state.cljs`
7. Frontend view changes in `views/meets.cljs`
8. Manual browser verification

## Open Questions

- Sort order for series list: manual (`sort_order`) only, or alphabetical? (Defaulting to `sort_order` for consistency with other entities)
- Should the sidebar filter state be fully shared with the meets view, or should series have its own filter state? (Plan: shared, so toggling "Series" preserves your current filter selections)
