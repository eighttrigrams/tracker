#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_FILE="$script_dir/hooks.log"

INPUT=$(cat)
TOOL_NAME=$(echo "$INPUT" | jq -r '.tool_name' 2>/dev/null)
TIMESTAMP=$(date +%Y-%m-%d\ %H:%M:%S)

if [ "$TOOL_NAME" = "Bash" ]; then
  CMD=$(echo "$INPUT" | jq -r '.tool_input.command' 2>/dev/null)
  DESC=$(echo "$INPUT" | jq -r '.tool_input.description // empty' 2>/dev/null)
  if [ -n "$DESC" ]; then
    echo "$TIMESTAMP - PreToolUse - Bash($CMD) - $DESC" >> "$LOG_FILE"
  else
    echo "$TIMESTAMP - PreToolUse - Bash($CMD)" >> "$LOG_FILE"
  fi
else
  echo "" >> "$LOG_FILE"
  echo "================================================================" >> "$LOG_FILE"
  echo "[$TIMESTAMP] PreToolUse" >> "$LOG_FILE"
  echo "================================================================" >> "$LOG_FILE"
  echo "$INPUT" | jq '{tool_name, tool_input}' >> "$LOG_FILE" 2>&1 || echo "$INPUT" >> "$LOG_FILE"
fi
