#!/bin/bash
set -e

if [ $# -lt 2 ]; then
    echo "Usage: $0 <title> <message-body> [sender]"
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/../.credentials"

TITLE="$1"
BODY="$2"
SENDER="${3:-System}"

TOKEN=$(curl -s -X POST "$TRACKER_API_URL/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\": \"$TRACKER_USERNAME\", \"password\": \"$TRACKER_PASSWORD\"}" \
    | grep -o '"token":"[^"]*"' | cut -d'"' -f4)

if [ -z "$TOKEN" ]; then
    echo "Failed to authenticate"
    exit 1
fi

curl -s -X POST "$TRACKER_API_URL/api/messages" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d "{\"sender\": \"$SENDER\", \"title\": \"$TITLE\", \"description\": \"$BODY\"}"

echo ""
