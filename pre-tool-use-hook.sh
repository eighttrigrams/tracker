#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_FILE="$script_dir/hooks.log"

INPUT=$(cat)

echo "" >> "$LOG_FILE"
echo "================================================================" >> "$LOG_FILE"
echo "[$(date +%Y-%m-%d\ %H:%M:%S)] PreToolUse" >> "$LOG_FILE"
echo "================================================================" >> "$LOG_FILE"
echo "$INPUT" | jq '{tool_name, tool_input}' >> "$LOG_FILE" 2>&1 || echo "$INPUT" >> "$LOG_FILE"
