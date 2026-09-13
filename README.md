# Tracker

Task Tracker | Scheduler | Planner 

https://tracker-ibh-lq.fly.dev/

Tracker's ontology: https://personalist.org/nolmev-tintep

## Quickstart

Run

```bash
$ make start
# Visit http://localhost:3110.
$ make stop
```

## Configuration

On first run, a `config.edn` is auto-created with defaults,
which allows to navigate the UI without having to log in, as well as
creating an initial (persistent) database.

For development, you can customise:

- `:db {:type :sqlite-file :path "data/tracker.db"}` - database location
- `:db {:type :sqlite-memory}` - use an in-memory database
- `:shadow? true` - enable hot reload for ClojureScript development
- `:dangerously-skip-logins? true` allows to navigate the app without requiring a login step, to facilitate development
- `:nrepl-port 7898` - nREPL port (required for dev mode)

For E2E testing, pass `:e2e true` via `clojure -X:run` to use an in-memory DB, skip logins, and skip nREPL.

## Development

### Prerequisites

- Clojure
- Node.js

### Running

Add `:shadow? true` to your `config.edn` for hot reload, then:

```bash
$ make start
```

With `scripts/start.sh`, you can use `PORT=<port>` for a custom port (default: 3027).

## Testing

See [docs/TESTING.md](docs/TESTING.md).

`make test` is the Clojure suite and `make test-cljs` is the ClojureScript one.
**`make test-all` is the one to run before believing a change to the seal**: the
two clients have to produce byte-identical ciphertext, and `make test` alone says
nothing about that.

## Encryption — one user's prose, and the server cannot read it

Tracker holds more than one person's data in one SQLite file, and one of them
seals. For that user every `description` body — task, issue, meeting, meeting
series, recurring task, journal, journal entry, resource, category — is encrypted
by the client that writes it. Titles, names, tags, badge titles, scopes, dates and
every association stay in the clear.

**The server holds no key and never will.** Tracker runs on fly; a key there would
make this a decoration with no error message to say so. What the server knows is
the string `enc:v1:` and which columns are prose (`et.tr.envelope`), which is
enough for the one thing only it can do — see *the gate* below.

```
enc:v1:<base64(nonce ‖ ciphertext ‖ tag)>    AES-256-GCM, fresh 96-bit nonce
```

Stored as text in the column it already lived in, so the seal needed no schema
change, and **mixed state is legal permanently**: a value without the prefix is
plaintext and passes through untouched.

### Where the line is

Prose is sealed; names and curated words are not. That is not a compromise — it is
the line the search already drew. Nine of the eleven body-carrying entities search
`[:title :tags]` and read a body never, so sealing costs the search nothing.

The two that are left out are left out for different reasons, and both are
decisions rather than omissions. **`messages`** are written by three producers
that hold no key and cannot be given one — the IMAP poller, blog's forwarding and
tracker's own feed worker — and the server parses those bodies to recover titles.
**`mottos`** because a motto's description is a second name for the same thing
(*Carpe Diem* / *Seize the day*), not prose, and its search reads it.

### The gate: the client seals, the server enforces

*Whose rows are these?* is the one question a client cannot answer — a CLI
guessing from a username is a guess that seals somebody else's prose. So the flag
is `users.seal_prose`, the server reads it, and the rule is:

> For a user whose `seal_prose` is 1, a write may not **introduce** new plaintext
> prose into a sealed column. An **echo** of the value already stored is not an
> introduction.

The echo half is what keeps a half-migrated database usable, and what stops a
no-op save being refused.

The guard is middleware, so it sees HTTP writes and **not** the writes tracker
makes to its own tables in process — the schedulers in `et.tr.worker` and the feed
crawler in `et.tr.source-worker`. That gap is real and, today, empty: the
schedulers create rows without touching `description` at all, and the crawler
writes into `messages`, which stays clear. It is a measurement of the code, not a
property of the design, so it is written down where somebody adding the next
in-process writer would meet it — in `et.tr.envelope/wrap-seal-guard`, with the
rule such a writer has to keep.

### The three rules, which live in the seal and not at its call sites

1. **Never seal a blank.** `nil` stays `nil`, `""` stays `""` —
   `db/journal_entry.clj prune-empty-entries` depends on it.
2. **Never re-seal an unchanged value.** Echo the stored value, whichever
   encoding. Fresh nonces otherwise turn every no-op save into an audit event
   holding two different envelopes of one unchanged sentence.
3. **Unsealing is prefix-driven and never throws.** A value that will not open
   comes back visibly, rather than taking a response down with nothing to say why.

### Where the code is

| | |
| --- | --- |
| `src/cljc/et/tr/seal_rules.cljc` | the pure half — prefix, binding, inventory, payload shapes. Read by all three implementations |
| `src/cljs/et/tr/ui/seal.cljs` | the browser, WebCrypto |
| `src/cljs/et/tr/ui/key_store.cljs` | a **non-extractable** `CryptoKey` in IndexedDB |
| `src/cljs/et/tr/ui/api.cljs` | the one door: unseal on the way in, seal on the way out |
| `src/clj/et/tr/envelope.clj` | the server's guard. No crypto in it |
| `test/fixtures/seal-vectors.edn` | what stops the implementations drifting |

`plurama-cli/tracker_seal.clj` is the fourth reader and lives in that repo.

**`api.cljs` is the only namespace allowed to touch `ajax.core`**, and
`test/unit/et/tr/api_is_the_only_door_test.clj` fails if that stops being true.
That rule exists because it once was not: thirteen list fetches went round it, so
no list page unsealed anything and an inline edit could seal a ciphertext a second
time.

### Operationally

Nothing is sealed until a key exists and `seal_prose` is armed, and every client
treats *no key* as *sealing off*. The two procedures are
`handoffs/tracker-seal-deploy-playbook.md` (ship it, where it does nothing) and
`handoffs/tracker-seal-playbook.md` (the key ceremony and the cutover).

## Deployment

```bash
$ make deploy
```

## Messaging

See [docs/MESSAGING.md](docs/MESSAGING.md).

## Configuration

Main config file is `config.edn`

- `:shadow?` whether hot code reload will be active when the application is started via `make start`
- `:nrepl-port`

## Environment variables

- `PORT`: Tracker's main port, default to `3110`
- `SHADOW_PORT`: Shadow CLJS primary HTTP; default to `9801`
