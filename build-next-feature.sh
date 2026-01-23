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
4. Make sure you get the unit tests running (`clj -X:tests`)

EOF
)" --allowedTools "Write"

if ! clj -X:test; then
    echo "Unit tests failed. Aborting."
    exit 1
fi

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
- PR_ARCHITECTURE_REVIEW_RESULT.md

Then look at the previous to last commit (against master). Its name is "$1 - stage 1"

From the reviews, and all what you know NOW, write a new NEXT_FEATURE_PROPER_IMPLEMENTATION_PLAN.md.

- Pick mostly high and medium issues from the todos. 
- Be specific which files the implementer should touch on this next, better attempt

EOF
)" --allowedTools "Write"

rm NEXT_FEATURE_JUSTIFICATION.md
rm PR_*_RESULT.md

echo "Please human, add your verdict in HUMAN_OPINION.md"

while true; do
    read -p "Type 'ok' to proceed: " input
    if [ "$input" = "ok" ]; then
        if [ -f "HUMAN_OPINION.md" ]; then
            break
        else
            echo "HUMAN_OPINION.md not found. Please create it first."
        fi
    fi
done

claude -p "$(cat <<EOF

Read
- NEXT_FEATURE_PROPER_IMPLEMENTATION_PLAN.md
- HUMAN_OPINION.md

Now implement the feature properly, according to that new plan!

Make sure tests run (`clj -X:test`)

Report what you did in NEXT_FEATURE_JUSTIFICATION.md

EOF
)" --allowedTools "Write"
