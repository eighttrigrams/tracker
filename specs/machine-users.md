# Machine Users

## Overview

A *machine user* is a special kind of account meant to be driven by a program
or agent (LLM, script, automation) rather than a human. Machine users
authenticate against the same `/api/*` endpoints the web UI uses, but their
write access is gated by recording mode. There is no separate `/rest/*`
namespace — read and write traffic for both humans and machines flows
through `/api`, and the server differentiates them by inspecting the JWT.

## Data model

The `users` table carries two extra columns:

- `is_machine_user INTEGER NOT NULL DEFAULT 0`
- `for_user_id INTEGER REFERENCES users(id)` — the regular ("human") user
  whose data this machine user acts on.

A partial unique index enforces **at most one machine user per regular
user**:

```
CREATE UNIQUE INDEX idx_users_one_machine_per_user
  ON users(for_user_id) WHERE is_machine_user = 1;
```

A machine user owns no entities of its own — its only role is to carry
credentials and a binding to a target user. When a regular user is deleted,
their bound machine user (if any) is deleted in the same transaction.

## Creation (admin only)

In the admin → Users page, the *Machine user* checkbox enables a target-user
select. Eligible targets are all non-machine users. Submitting creates a
new row with `is_machine_user=1` and `for_user_id=<target>`. The backend
rejects the request when:

- the target user does not exist, or is itself a machine user (`400`)
- the target already has a machine user (`409`)
- machine flag is set but no target is supplied (`400`)
- the username is already taken (`409`)

Machine users do **not** appear in the available-users switcher.

## Authentication

Machine users authenticate the same way as regular users:

```
POST /api/auth/login   {"username": "...", "password": "..."}
→ { "token": "<jwt>", "user": {...} }
```

Their JWT carries two extra claims:

- `is-machine-user: true`
- `for-user-id: <target user id>`

Subsequent requests use `Authorization: Bearer <jwt>`. The server resolves
the request's effective `user-id` to `for-user-id`, so handlers operate on
the bound user's tasks, meets, journal entries, etc.

## Recording-mode gate

A single in-memory atom (`et.tr.server.recording-mode/*recording?`) tracks
whether the gate is open. It is **off** at startup. The atom is flipped by:

- `POST /api/recording-mode/toggle` (used by the UI keybinding **Alt+Shift+W**)
- The toggle is **human-only**. There is no policy reason for a machine to
  enable its own gate, and agents must not flip it.

The middleware `wrap-machine-write-guard` runs on every request:

1. Pass through unless the request targets `/api/*` and uses a mutating
   method (`POST`/`PUT`/`DELETE`).
2. Extract and verify the Bearer JWT. Pass through if absent, invalid, or
   not flagged `is-machine-user`.
3. Log the intent (URI, method, machine user id, for-user-id, recording
   state) regardless of outcome.
4. If recording mode is on, run the handler. Otherwise return
   `{"dropped": true}` (HTTP 200) without invoking the handler.

Read access (`GET`) is never gated.

Regular users (humans authenticated to the UI) are not subject to the gate.

## Today board

`GET /api/today-board` returns `{tasks, meets, journal-entries}` in a single
fetch — the canonical machine-friendly aggregate. Tasks use the same
`:today` filter as the UI Today list. Meets are those with `start_date =
today` and not archived. Journal entries are today's entries.

## Lifecycle

- Deleting a regular user cascades to the bound machine user.
- Deleting a machine user directly removes only its row (it owns nothing).
- A user cannot be promoted into a machine user after creation, nor
  demoted. The machine flag is set at creation time.
