# Tracker 

Task Tracker | Scheduler | Planner

https://tracker-ibh-lq.fly.dev/

## Quickstart

Run

```bash
$ make start
```

Visit http://localhost:3027.

To stop:

```bash
$ make stop
```

## Configuration

On first run, `config.edn` is auto-created with defaults. Modify it to customize:

- `:shadow? true` - enable hot reload for ClojureScript development
- `:db {:type :sqlite-file :path "data/tracker.db"}` - database location

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

## Deployment

```bash
$ make deploy
```
