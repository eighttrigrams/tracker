#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR/.."

docker build -f "$PROJECT_DIR/Dockerfile.e2e" -t tracker-e2e "$PROJECT_DIR"

LOG_FILE="$PROJECT_DIR/logs/tracker.e2e.log"
mkdir -p "$PROJECT_DIR/logs"

docker run --rm tracker-e2e 2>&1 | tee "$LOG_FILE"
TEST_EXIT="${PIPESTATUS[0]}"

if [ "$TEST_EXIT" -eq 0 ]; then
  TITLE="Tracker E2E Tests Passed"
else
  TITLE="Tracker E2E Tests Failed"
fi

BODY=$(cat "$LOG_FILE")

if [ -f "$PROJECT_DIR/.credentials" ]; then
  "$SCRIPT_DIR/send-message.sh" "$TITLE" "$BODY" "E2E Testing"
fi

exit "$TEST_EXIT"
