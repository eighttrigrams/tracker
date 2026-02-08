#!/bin/bash
set -e

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
