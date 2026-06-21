# Pagination & Lazy Loading

## Philosophy

Several list views in the tracker can grow without bound — past meets,
resources, and reports being the worst offenders. Loading *everything* on
every visit is wasteful on two axes:

1. **Row count** — too many rows travel over the wire and get rendered, even
   though the user only ever looks at the top of the list.
2. **Per-row weight** — every row historically dragged its `description`
   along, even though the description is only ever shown when a card is
   expanded.

The goal is to load *as little as is useful*, and to load the rest only when
the user actually asks for it. Two mechanisms achieve this:

- **"See more" pagination** — load a first page, then append further pages on
  demand.
- **Lazy descriptions** — keep list rows lean (no `description`) and fetch a
  card's body only when it is expanded.

Neither mechanism should ever change *what* the user can see — only *when* it
is fetched. Correctness of filtering and search must never be traded away for
a smaller payload (see "Why gating exists").

## When we paginate vs. fetch everything

Pagination is only safe and useful for an **unfiltered** list. The rule:

| Condition | Behaviour |
|---|---|
| Neither a search term (≥ 2 chars) **nor** any filter active | **Paginate**: first page (50) + "See more" |
| A search term (≥ 2 chars) **or** any filter active | **Fetch the full** (already-narrowed) set; no "See more" |
| Inherently bounded views (e.g. the Today page) | Always **full** |

"Any filter" means category (people/places/projects/goals), importance,
domain, excluded-domains, and the work/private context — anything that
narrows the set. When narrowed, the result set is small enough that a single
full fetch is cheap, and — critically — correct.

### Why gating exists (do not remove this)

Some filtering still runs **client-side** (`et.tr.filters`, e.g. the `q`
prefix search). If we paginated while a filter were active, the client would
filter only the rows it happened to have fetched so far, producing wrong and
incomplete results. So whenever a filter is active we must hold the **full**
(narrowed) dataset client-side. The unfiltered case is the only one where
server-side pagination is both safe and worthwhile.

This is a deliberate, conservative line. A later refinement could push `q`
fully server-side and allow pagination during search; until then, gating is
the contract.

## When we load descriptions

- **Normal list loads are lean**: list endpoints omit `description` by
  default. Lists carry titles, badges, tags, dates, categories — everything
  needed to render a collapsed card — but not the body.
- **On expand**: when a card is uncollapsed, if its description has not been
  loaded yet, fetch the full single item (`GET /api/<type>/:id`, which always
  returns the complete row) and merge it into the cached list row. Editing the
  description persists via `PUT` and updates the cached row so the change
  shows immediately, with no full-list refetch.
- **Re-expand does not refetch**: the presence of the `:description` key on
  the cached row is the "already loaded" guard.
- **Exception — description-centric views**: the notepad / summary modes (the
  📋 views, e.g. journal summary) are *about* the bodies, so they fetch
  descriptions eagerly (`?detail=full`) rather than lazily.

### Guard: a lean row's edit-modal save MUST omit an unloaded `description`

This is a latent landmine, documented here so the next person making a list
view lean does not step on it. The server PUT overwrites `description`
**unconditionally** (no field-level partial update: `task_handler.clj` writes
`:description (or description "")`). So the *only* thing standing between a
not-yet-loaded body and a silent wipe is the client omitting `:description`
from the save payload when it was never fetched.

Today only `:resource` does that — its edit-modal save guards with "send
`@description` only if the row already carries `:description` **or** the field
is non-empty, else `nil`" — because `:resource` is the only type served as a
lean row (lazy-fetched on expand). Every other type (`:task`, `:meet`,
`:journal`, `:journal-entry`, `:message`) still sends `@description`
unconditionally, which is safe **only because** their list rows currently carry
the real `:description` inline, so their modals always open with the true body.

If lean list rows are ever extended to those types (or the reports endpoint is
made lean), their edit-modal save **must** be changed to the `:resource`
pattern — omit an unloaded `:description` — **and** the save should be gated
until the body has loaded. Otherwise opening a lean card and editing only its
title (or saving before the lazy body fetch resolves) will overwrite the real
server-side description with an empty string. The nil-omission belongs uniform
across types, not resource-only.

## Key design decisions & rationale

- **Page size 50.** A pragmatic default; large enough that most sessions never
  need a second page, small enough to keep the payload light. Tunable later.
- **`has_more` via a `limit+1` probe.** The list endpoint fetches `limit + 1`
  rows; if it gets more than `limit` it returns exactly `limit` rows and
  signals `has_more: true`. This avoids a wasted final "See more" that returns
  nothing, without a second `count(*)` query.
- **Opt-in response envelope.** The list endpoint returns a **bare vector** by
  default. Only when the caller passes `paged=true` does it wrap the result as
  `{:items [...], :has_more <bool>}`. This keeps the machine-user contract
  (a plain list) untouched — see below — while giving the UI the signal it
  needs. The UI always sends `paged=true` and reads `:items` / `:has_more`.
- **Lean by query param, mirroring the machine-lean mechanism.** `detail=full`
  opts a request back into descriptions; otherwise list rows are lean. We drop
  only `:description`, never `:tags` — tags are needed to render and filter
  collapsed cards.
- **`offset` alongside the existing `limit`.** Pagination is plain SQL
  `LIMIT/OFFSET` over a stable `ORDER BY`. The next-page offset is derived from
  the live loaded row count (`count` of the current list) at See-more time. A
  delete removes the row from both the client list and the server result set, so
  the two stay in lockstep and the page boundary lands correctly.
- **Reset on change.** Any change to search, filter, sort, or scope resets
  pagination to the first page. An in-flight-request id guards against a slow
  earlier fetch clobbering a newer one.

## The plurama / tracker bot: machine-user token economy

The tracker is driven not only by humans in the web UI but by a **machine
user** — the plurama / tracker bot, an LLM agent that reads and writes through
the same `/api/*` endpoints (see [machine-users.md](machine-users.md) for the
account model, auth, and the recording-mode write gate). For an LLM, every row
and every field is **tokens**, and tokens are the scarce resource. A naive
list endpoint that returns hundreds of rows, each with a long `description`,
can blow an agent's context window on a single call. The whole point of the
lean/paginated design is therefore doubly important for the bot: it is what
keeps automated traffic affordable and within context limits.

Three server-side mechanisms protect machine users, all keyed off the verified
JWT's `is-machine-user` claim so that **human UI traffic is never affected**:

1. **Default row cap (`wrap-machine-default-limit`).** A machine list `GET`
   with no explicit `?limit` has `limit=100` injected before the handler runs.
   A caller-supplied `?limit` is always honoured. This stops an agent from
   accidentally pulling an unbounded list.
2. **Lean list rows (`wrap-machine-lean`).** For a verified machine caller,
   list responses have `:description` and `:tags` stripped from every row,
   unless the request opts in with `?detail=full`. Map-bodied responses
   (the today-board aggregate, single-item `GET`s) pass through untouched, so
   an agent can still fetch a full item by id when it actually needs the body.
   This runs on the Clojure response body just before JSON serialisation.
3. **The today-board aggregate.** `GET /api/today-board` returns
   `{tasks, meets, journal-entries}` in one bounded, machine-friendly fetch —
   the canonical "what matters now" call — instead of the agent paginating
   three separate lists.

Note the symmetry with the UI: the UI gets lean rows via the `detail` query
param and the `paged` envelope it sends itself; the bot gets lean rows and a
default cap imposed *for* it by middleware. The bot never sends `paged=true`,
so it always receives a plain vector — its contract is stable regardless of
the UI's pagination evolving. `detail=full` is the single, shared escape hatch
back to full payloads for both.

### Reports are bounded by default — a deliberate, reports-specific exception

`GET /api/reports` is the one list endpoint where the machine-user response is
*not* "unchanged". The week-window is applied to **every** caller, including a
param-less bot call: with no `weekOffset` / `weekLimit` it returns the
most-recent **4 weeks** (`weekOffset=0`, `weekLimit=4`) plus an accurate
`has_more`, never the full unbounded history. This is on purpose — an unbounded
report (potentially years of completed tasks, meets, and journal entries, each
a row) is exactly the context-window blowout the lean/paginated design exists
to prevent, and reports have no row-count `limit` to fall back on. The bot
reaches older data by paging `weekOffset` (0, 4, 8, …) and stops when
`has_more` is false; full history is reachable, just never in a single fetch.
`weekLimit` remains overridable by an explicit param. (Reports return a
map-bodied envelope `{tasks, meets, journal_entries, has_more}` with no
`:items` key, so `wrap-machine-lean` passes the body through untouched — the
bounding is the handler's own week-window, not the lean middleware.)

## Special cases

### The Today page (and other bounded views)

The Today page is inherently bounded — it shows today plus a short horizon of
upcoming days (see [today-page.md](today-page.md)). It is never paginated and
always returns its full set. The same holds for any view whose size is bounded
by construction. "See more" simply does not apply there.

### Partitioning along natural lines

Some views are not flat lists; they are **grouped by a natural time unit**, and
pagination must respect those boundaries rather than cut through them.

- **Reports — by week.** The reports view groups completed tasks, meets, and
  journal entries into **week → day** sections. Pagination must be
  **week-aligned**: a page shows whole, complete weeks; "See more" reveals
  further complete weeks back in time. A row-count cut (the resources `limit+1`
  probe) is *wrong* here because it would slice a week in half. The signal is
  "more weeks exist before the current cutoff", driven by a week-aligned date
  cutoff applied consistently across all three sources.
  - **Done tasks — by full day.** Within reports, completed tasks are bucketed
    by their `done_at` day. The cutoff snaps to a whole day: never show a
    partial day of done tasks. Day-completeness nests inside week-completeness.

- **Meets — by week.** Both upcoming and past meets are partitioned into
  **week sections with week headers** in the UI (not just for pagination — the
  visual structure itself). "See more" loads whole weeks at a time: backward in
  time for past meets, forward for upcoming. Same week-aligned cutoff
  mechanism as reports, not the row-count probe.

## Implementation status

- **Resources** — implemented end to end: `offset`/`detail`/`paged` on the
  list endpoint, `has_more` probe, "See more", gating, and lazy descriptions
  on expand.
- **Past meets, reports** — planned, following the week-partitioned rules
  above.
- **Later** — tune page size; consider pushing `q` server-side to lift the
  "fetch-all while searching" restriction.
