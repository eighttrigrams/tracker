# Tracker Operations Manual

## Environment Variables

### Rate Limiting

| Variable | Default | Description |
|----------|---------|-------------|
| `RATE_LIMIT_MAX_REQUESTS` | 60 | Maximum requests allowed per window |
| `RATE_LIMIT_WINDOW_SECONDS` | 60 | Time window in seconds |

### Setting on Fly.io

```bash
fly secrets set RATE_LIMIT_MAX_REQUESTS=120 RATE_LIMIT_WINDOW_SECONDS=60
```
