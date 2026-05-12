#!/bin/bash

PORT=$(bb -e '(:port (read-string (slurp "config.edn")))' 2>/dev/null)
PORT=${PORT:-3027}

# Refuse to stop a server started in the other environment. Without this, a
# host-side `make stop` while the server runs in docker would kill Docker's
# port-forward proxy on $PORT (two PIDs on macOS — IPv4 + IPv6 listeners),
# collapsing the container's networking. The reverse mismatch silently misses
# the real PID. The lockfile is written by start.sh.
if [ -f /.dockerenv ]; then
  CURRENT=container
else
  CURRENT=host
fi

if [ -f .dev-server.lock ]; then
  LOCKED=$(cat .dev-server.lock)
  if [ "$LOCKED" != "$CURRENT" ]; then
    echo "ERROR: dev server was started in '$LOCKED' env, refusing to stop from '$CURRENT'."
    echo "Run stop from the matching environment, or remove .dev-server.lock to override."
    exit 1
  fi
fi

echo "Stopping server on port $PORT..."
PID=$(lsof -ti:$PORT)
if [ -n "$PID" ]; then
  kill $PID
  echo "Killed server process $PID"
else
  echo "No server found on port $PORT"
fi

echo "Stopping shadow-cljs server..."
npx shadow-cljs stop 2>/dev/null || true
rm -f .shadow-cljs.pid .nrepl-port .dev-server.lock
echo "Done."
