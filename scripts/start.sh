#!/bin/bash
set -e

if [ ! -f config.edn ]; then
  echo "Creating default config.edn..."
  cat > config.edn << 'EOF'
{:db {:type :sqlite-file
      :path "data/tracker.db"}
 :port #long #or [#env PORT 3110]
 :dangerously-skip-logins? true}
EOF
fi

# Mark which environment owns the dev server. stop.sh reads this to refuse a
# cross-env stop: on macOS, `lsof -ti:$PORT` from the host returns Docker's
# port-forward proxy PIDs, and killing those tears down the container's
# networking. Conversely, stopping from inside the container while the server
# was started on the host would miss the real PID entirely.
if [ -f /.dockerenv ]; then
  echo container > .dev-server.lock
else
  echo host > .dev-server.lock
fi

if [ ! -d node_modules ]; then
  echo "Installing npm dependencies..."
  npm install
fi

# SHADOW=false to skip hot reload and run a release build instead.
if [ "${SHADOW:-true}" = "true" ]; then
  echo "Starting shadow-cljs watch..."
  npx shadow-cljs watch app &
  echo $! > .shadow-cljs.pid
  sleep 3
else
  echo "Building ClojureScript..."
  npx shadow-cljs release app
fi

echo "Starting server in development mode..."
DEV=true clojure -X:run
