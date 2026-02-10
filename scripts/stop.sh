#!/bin/bash

EXPLICIT_PORT=$PORT
PORT=${PORT:-$(cat .server.port 2>/dev/null || echo 3027)}

echo "Stopping server on port $PORT..."
PID=$(lsof -ti:$PORT)
if [ -n "$PID" ]; then
  kill $PID
  echo "Killed server process $PID"
else
  echo "No server found on port $PORT"
fi

if [ -z "$EXPLICIT_PORT" ]; then
  echo "Stopping shadow-cljs server..."
  npx shadow-cljs stop 2>/dev/null || true
  rm -f .shadow-cljs.pid .nrepl-port .server.port
fi
echo "Done."
