# Tracker

Task Tracker | Scheduler | Planner

https://tracker-ibh-lq.fly.dev/

## Quickstart

Run

```bash
$ make start
# Visit http://localhost:3027.
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

### Test production build locally

```bash
$ make start-prod
```

## Testing

See [docs/TESTING.md](docs/TESTING.md).

## Deployment

```bash
$ make deploy
```

## Messaging

See [docs/MESSAGING.md](docs/MESSAGING.md).

## Automated Feature Building

Requires [builder](https://github.com/eighttrigrams/builder) installed via bbin:

```bash
bbin install io.github.eighttrigrams/builder
```

Then run:

```bash
bb scripts/bb/build_next_feature.clj <feature-name>
```
