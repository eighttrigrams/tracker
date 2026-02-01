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
elif [ "$TOOL_NAME" = "Task" ]; then
  SUBAGENT=$(echo "$INPUT" | jq -r '.tool_input.subagent_type // empty' 2>/dev/null)
  DESC=$(echo "$INPUT" | jq -r '.tool_input.description // empty' 2>/dev/null)
  PROMPT=$(echo "$INPUT" | jq -r '.tool_input.prompt // empty' 2>/dev/null)
  echo "$TIMESTAMP - PreToolUse - Task($SUBAGENT) - $DESC" >> "$LOG_FILE"
  echo "  prompt: $PROMPT" >> "$LOG_FILE"
elif [ "$TOOL_NAME" = "Skill" ]; then
  SKILL=$(echo "$INPUT" | jq -r '.tool_input.skill // empty' 2>/dev/null)
  ARGS=$(echo "$INPUT" | jq -r '.tool_input.args // empty' 2>/dev/null)
  if [ -n "$ARGS" ]; then
    echo "$TIMESTAMP - PreToolUse - Skill($SKILL) - $ARGS" >> "$LOG_FILE"
  else
    echo "$TIMESTAMP - PreToolUse - Skill($SKILL)" >> "$LOG_FILE"
  fi
elif [ "$TOOL_NAME" = "Write" ]; then
  FILE_PATH=$(echo "$INPUT" | jq -r '.tool_input.file_path // empty' 2>/dev/null)
  echo "$TIMESTAMP - PreToolUse - Write($FILE_PATH)" >> "$LOG_FILE"
elif [ "$TOOL_NAME" = "Edit" ]; then
  FILE_PATH=$(echo "$INPUT" | jq -r '.tool_input.file_path // empty' 2>/dev/null)
  echo "$TIMESTAMP - PreToolUse - Edit($FILE_PATH)" >> "$LOG_FILE"
else
  echo "" >> "$LOG_FILE"
  echo "================================================================" >> "$LOG_FILE"
  echo "[$TIMESTAMP] PreToolUse" >> "$LOG_FILE"
  echo "================================================================" >> "$LOG_FILE"
  echo "$INPUT" | jq '{tool_name, tool_input}' >> "$LOG_FILE" 2>&1 || echo "$INPUT" >> "$LOG_FILE"
fi
