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

## API

### Sending messages (Admin only)

Messages can be sent to the app via the API using admin credentials:

```bash
curl -X POST http://localhost:3027/api/messages \
  -H "Content-Type: application/json" \
  -H "X-User-Id: null" \
  -d '{"sender": "John", "title": "Hello", "description": "Message body"}'
```

The `X-User-Id: null` header indicates the admin user (dev mode only). Messages are visible in the Mail tab (admin only).

### Sending messages in production

Create a `.credentials` file (gitignored):

```
TRACKER_USERNAME=admin
TRACKER_PASSWORD=your-admin-password
TRACKER_API_URL=https://your-prod-url.fly.dev
```

Then use the script:

```bash
./scripts/send-message.sh "Title" "Message body"
./scripts/send-message.sh "Title" "Message body" "CustomSender"
```

## Automated Feature Building

Requires [builder](https://github.com/eighttrigrams/builder) installed via bbin:

```bash
bbin install io.github.eighttrigrams/builder
```

Then run:

```bash
bb scripts/bb/build_next_feature.clj <feature-name>
```
