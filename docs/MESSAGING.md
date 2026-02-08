# Messaging

Messages can be sent to the app and appear in the Mail tab (admin only).

## Dev Mode

```bash
curl -X POST http://localhost:3027/api/messages \
  -H "Content-Type: application/json" \
  -H "X-User-Id: null" \
  -d '{"sender": "John", "title": "Hello", "description": "Message body"}'
```

`X-User-Id: null` indicates the admin user in dev mode.

## Production

### Setup

Create a `.credentials` file (gitignored):

```
TRACKER_USERNAME=admin
TRACKER_PASSWORD=your-admin-password
TRACKER_API_URL=https://your-prod-url.fly.dev
```

### Sending

```bash
./scripts/send-message.sh "Title" "Message body"
./scripts/send-message.sh "Title" "Message body" "CustomSender"
```

The script authenticates via `/api/auth/login`, obtains a bearer token, then posts the message. The sender defaults to "System" if not specified.
