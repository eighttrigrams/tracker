#!/bin/bash

PORT=$(bb -e '(:port (read-string (slurp "config.edn")))')
if [ -z "$PORT" ]; then
  echo "ERROR: Could not read :port from config.edn"
  exit 1
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
rm -f .shadow-cljs.pid .nrepl-port
echo "Done."
