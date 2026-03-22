# Implementation Plan: Meeting Series

Based on [specs/meeting-series.md](meeting-series.md).
Branch: `add-meeting-series`.

## Phase 1: Database Migration

### 026-add-meeting-series

Single migration with three statements:

1. New `meeting_series` table (same shape as `meets` minus `start_date`/`start_time`):

| Column | Type | Notes |
|---|---|---|
| id | INTEGER PK AUTOINCREMENT | |
| title | TEXT NOT NULL | |
| description | TEXT DEFAULT '' | |
| tags | TEXT DEFAULT '' | |
| created_at | DATETIME | DEFAULT datetime('now') |
| modified_at | DATETIME | DEFAULT datetime('now') |
| sort_order | REAL NOT NULL DEFAULT 0 | |
| scope | TEXT NOT NULL DEFAULT 'both' | CHECK private/both/work |
| importance | TEXT NOT NULL DEFAULT 'normal' | CHECK normal/important/critical |
| user_id | INTEGER | FK to users |

Index: `idx_meeting_series_user_scope (user_id, scope)`.

2. New `meeting_series_categories` table (same pattern as `meet_categories`):

| Column | Type |
|---|---|
| meeting_series_id | INTEGER NOT NULL (FK to meeting_series) |
| category_type | TEXT NOT NULL |
| category_id | INTEGER NOT NULL |

PK: `(meeting_series_id, category_type, category_id)`.

3. Add nullable column to `meets`:

```sql
ALTER TABLE meets ADD COLUMN meeting_series_id INTEGER REFERENCES meeting_series(id) ON DELETE SET NULL;
```

This establishes the 1:n relationship. Regular one-off meetings have `meeting_series_id = NULL`.

## Phase 2: Backend DB Functions (`db.clj`)

Follow the existing meets pattern. Add:

- `add-meeting-series [ds title scope user-id]` — insert with max sort_order + 1
- `list-meeting-series [ds opts]` — query with search, importance, scope, category filters (no date/sort-mode filtering since series have no dates)
- `get-meeting-series [ds id]` — fetch single with categories
- `update-meeting-series [ds id title description tags]` — update title/description/tags + modified_at
- `delete-meeting-series [ds id]` — cascade delete categories, then series (in transaction). Also set `meeting_series_id = NULL` on related meets.
- `set-meeting-series-field [ds id field value]` — for scope and importance
- `categorize-meeting-series [ds id category-type category-id]`
- `uncategorize-meeting-series [ds id category-type category-id]`

The `list-meeting-series` query reuses `build-search-clause`, `build-importance-clause`, `build-scope-clause`, and `build-category-subquery` — just targeting `meeting_series` / `meeting_series_categories` instead.

## Phase 3: Backend API Routes (`server.clj`)

| Method | Path | Handler |
|---|---|---|
| GET | /api/meeting-series | list-meeting-series-handler |
| POST | /api/meeting-series | add-meeting-series-handler |
| GET | /api/meeting-series/:id | get-meeting-series-handler |
| PUT | /api/meeting-series/:id | update-meeting-series-handler |
| DELETE | /api/meeting-series/:id | delete-meeting-series-handler |
| PUT | /api/meeting-series/:id/:field | set-meeting-series-field-handler |
| POST | /api/meeting-series/:id/categorize | categorize-meeting-series-handler |
| DELETE | /api/meeting-series/:id/uncategorize | uncategorize-meeting-series-handler |

Query params for GET list: `q`, `importance`, `context`, `strict`, `people`, `places`, `projects`, `goals` (same as meets, minus `sort`).

## Phase 4: Frontend State

### `state/meeting-series.cljs` (new file)

New page state atom `*meeting-series-page-state`:

```clojure
{:expanded-series nil
 :editing-series nil
 :confirm-delete-series nil
 :filter-search ""
 :importance-filter nil
 :fetch-request-id 0}
```

Functions: `fetch-meeting-series`, `add-meeting-series`, `update-meeting-series`, `delete-meeting-series`, `set-meeting-series-scope`, `set-meeting-series-importance`, `categorize-meeting-series`, `uncategorize-meeting-series`, `set-expanded-series`, `set-series-filter-search`, `set-series-importance-filter`.

### `state.cljs` additions

- Store `meeting-series` list in `*app-state`
- Add `:meets-page/series-mode` boolean to `*app-state` (default `false`)
- Wrapper functions for all meeting-series CRUD
- `toggle-series-mode` — flips `:meets-page/series-mode`, triggers fetch of meeting-series or meets accordingly
- Wire up `fetch-meeting-series` with shared filters (people, places, projects) + meets-specific goal filter

### Meets page state change

Add `:series-mode` awareness to meets page: when series mode is on, the meets page fetches and displays meeting series instead of meets.

## Phase 5: Frontend View

### Meets header row change (`meets-tab` in `views/meets.cljs`)

Current layout: `[heading] [importance-filter] [sort-mode]`

New layout: `[heading] [importance-filter] [sort-mode] [series-toggle]`

The `series-toggle` is a single button labeled with `(t :meets/series)`. When active, it gets CSS class `"active"`. Clicking it calls `state/toggle-series-mode`.

When series mode is **on**, `sort-mode-toggle` is not rendered.

### Meeting series list (`views/meets.cljs` or new `views/meeting-series.cljs`)

When series mode is on, the main content area renders meeting series items instead of meet items. Two options:

**Option A** (preferred): Keep in `views/meets.cljs` with a conditional branch in `meets-tab`. The series item components can live in the same file since they share most structure with meet items.

**Option B**: Extract to `views/meeting-series.cljs` and compose in `meets-tab`.

### Meeting series item

Like a meet item but:
- **No date/time** in header or expanded view (no `meet-date-time-pickers`)
- **No urgency** selector
- **Has**: title, importance badge, description, category selectors (people, places, projects, goals), scope selector, importance selector, relation badges, edit button, delete button, tags

### Search/Add form in series mode

When series mode is on, the search-add form calls `state/add-meeting-series` / `state/set-series-filter-search` instead of the meet equivalents.

### Sidebar filters

Category filters work the same way for both modes — same sidebar, same shared filters. No changes needed to the sidebar itself, just the fetch that runs when filters change.

## File Change Summary

| File | Change |
|---|---|
| `resources/migrations/026-add-meeting-series.edn` | New (table + categories table + FK on meets) |
| `src/clj/et/tr/db.clj` | Add ~80 lines of meeting series DB functions |
| `src/clj/et/tr/server.clj` | Add ~60 lines of handlers + routes |
| `src/cljs/et/tr/ui/state/meeting-series.cljs` | New, ~120 lines |
| `src/cljs/et/tr/ui/state.cljs` | Add ~40 lines of wrappers |
| `src/cljs/et/tr/ui/views/meets.cljs` | Add series toggle + conditional rendering, ~80 lines |

## Implementation Order

1. Migration (026)
2. DB functions in `db.clj`
3. API routes in `server.clj`
4. Frontend state (`meeting-series.cljs` + `state.cljs`)
5. Frontend view (toggle button, series items, conditional rendering)
6. Manual testing in browser
