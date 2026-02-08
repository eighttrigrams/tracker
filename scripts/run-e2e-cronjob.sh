#!/bin/bash
set -e

# cron runs with a minimal PATH (/usr/bin:/bin), so git, make, docker etc. won't be found
export PATH="/opt/homebrew/bin:/usr/local/bin:/Users/daniel/.docker/bin:$PATH"

REPO_DIR="$1"

if [ -z "$REPO_DIR" ]; then
  echo "Usage: $0 <repo-directory>"
  exit 1
fi

cd "$REPO_DIR"

git fetch origin main
git reset --hard origin/main

make e2e-docker
