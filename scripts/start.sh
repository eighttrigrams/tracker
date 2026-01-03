#!/bin/bash
set -e

MODE=${1:-dev}

if [ ! -f config.edn ]; then
  echo "Creating default config.edn..."
  cat > config.edn << 'EOF'
{:db {:type :sqlite-file
      :path "data/tracker.db"}
 :dangerously-skip-logins? true}
EOF
fi

PORT=${PORT:-3027}
echo $PORT > .server.port

SKIP_LOGINS=$(grep -o ':dangerously-skip-logins? true' config.edn || true)

if [ "$MODE" = "prod" ]; then
  if [ -n "$SKIP_LOGINS" ]; then
    echo "ERROR: Cannot start in prod mode with :dangerously-skip-logins? true"
    exit 1
  fi

  echo "Building uberjar..."
  clj -T:build uber

  echo "Starting in production mode on port $PORT..."
  java -jar target/tracker-0.0.1-standalone.jar
else
  if [ ! -d node_modules ]; then
    echo "Installing npm dependencies..."
    npm install
  fi

  SHADOW=$(grep -o ':shadow? true' config.edn || true)

  if [ -n "$SHADOW" ]; then
    echo "Starting shadow-cljs watch..."
    npx shadow-cljs watch app &
    echo $! > .shadow-cljs.pid
    sleep 3
  else
    echo "Building ClojureScript..."
    npx shadow-cljs release app
  fi

  echo "Starting server in development mode on port $PORT..."
  DEV=true clojure -X:run
fi
