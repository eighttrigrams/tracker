#!/bin/bash
set -e

# cron runs with a minimal PATH (/usr/bin:/bin) — docker is not found without this
export PATH="/opt/homebrew/bin:/usr/local/bin:/Users/daniel/.docker/bin:$PATH"

REPO_DIR="$1"

if [ -z "$REPO_DIR" ]; then
  echo "Usage: $0 <repo-directory>"
  exit 1
fi

LOCKFILE="$REPO_DIR/.e2e-cronjob.lock"
if [ -f "$LOCKFILE" ]; then
  echo "Previous run still in progress, skipping"
  exit 0
fi
trap 'rm -f "$LOCKFILE"' EXIT
touch "$LOCKFILE"

cd "$REPO_DIR"

git fetch origin main
git reset --hard origin/main

make e2e-docker
