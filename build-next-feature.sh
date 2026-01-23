#!/bin/bash

if [ -z "$1" ]; then
  echo "Usage: $0 <feature-name>"
  exit 1
fi

claude -p "$(cat <<EOF

We are building a new feature $1.
If it doesnt exist or we are not in it, create a new branch feature/$1 and switch to it.

1. Read the description what to build from NEXT_FEATURE.md.
2. Implement the feature.
3. Explain how what you have done matches what was asked of you. Write this to NEXT_FEATURE_JUSTIFICATION.md

EOF
)" --allowedTools "Write"

git add . && git commit -m "$1 - stage 1"

claude -p "$(cat <<EOF

Start three code reviewer subagents, to look at the current diff against master.

1. Does an architecture review; results go to PR_ARCHITECTURE_REVIEW_RESULT.md
# SKIP 2. Does a security review; results go to PR_SECURITY_REVIEW_RESULT.md
# SKIP 3. Does a data consistency review; results go to PR_DATA_CONSISTENCY_REVIEW_RESULT.md    

EOF
)" --allowedTools "Write"

cp NEXT_FEATURE_JUSTIFICATION.md /tmp/NEXT_FEATURE_JUSTIFICATION.md.bak
git revert HEAD --no-edit
cp /tmp/NEXT_FEATURE_JUSTIFICATION.md.bak NEXT_FEATURE_JUSTIFICATION.md

## By now, we have two new commits in our branch, and some unstaged PR files

claude -p "$(cat <<EOF

Read
- NEXT_FEATURE.md
- NEXT_FEATURE_JUSTIFICATION.md
- READ PR_ARCHITECTURE_REVIEW_RESULT.md

Then look at the previous to last commit (against master). Its name is "$1 - stage 1"

From the reviews, and all what you know NOW, write a new NEXT_FEATURE_PROPER_IMPLEMENTATION_PLAN.md.
Pick mostly high and medium issues from the todos.

EOF
)" --allowedTools "Write"

rm NEXT_FEATURE_JUSTIFICATION.md
rm PR_*_RESULT.md

read -p "Type 'continue' to proceed: " input
if [ "$input" != "continue" ]; then
    echo "Aborted."
    exit 1
fi