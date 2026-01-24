#!/bin/bash

FAVORITE_EDITOR_CMD="code"

if [ -z "$1" ]; then
  echo "Usage: $0 <feature-name>"
  exit 1
fi

current_branch=$(git branch --show-current)
if [ "$current_branch" != "main" ]; then
  echo "Error: Must be on main branch. Currently on: $current_branch"
  exit 1
fi

if [ -n "$(git status --porcelain)" ]; then
  echo "Error: Untracked or modified files present. Please clean up first."
  exit 1
fi

if git show-ref --verify --quiet "refs/heads/feature/$1"; then
  echo "Error: Branch feature/$1 already exists."
  exit 1
fi

git checkout -b "feature/$1"

make stop
rm -r .playwright-mcp
rm hooks.log

claude -p "$(cat <<EOF

We are building a new feature $1.

1. Read the description what to build from NEXT_FEATURE.md.
2. Implement the feature.
  - if it touches the user interface (which is almost always the case)
    - use a skill
    - take screenshots for proof of important key aspects
3. Make sure you get the unit tests running (`clj -X:test`)    
4. Now that you have an implementation which matches the specification of the new feature handed to you, consider the following: What things did you encounter which caused
  you extra work which wouldnt otherwise have occurred where the code structured in a cleaner way? 
  - Write the cleaner ways down in a BOYSCOUT_OBSERVATIONS.md 
    - Make sure nothing specifically pertaining to the new feature goes here. For the purpose of that report, we care about the state of the codebase we found, pretending we dont know what we build next.
5. Explain how what you have done matches what was asked of you. 
    - Write this to NEXT_FEATURE_JUSTIFICATION.md
        - If you have taken screenshots, list names of screenshots in this doc (prefix the names with where they are stored, namely .playwright-mcp/)    

EOF
)" --allowedTools "Write"

if ! clj -X:test; then
    echo "Unit tests failed. Aborting."
    exit 1
fi

git add . 
git reset HEAD -- BOYSCOUT_OBSERVATIONS.md NEXT_FEATURE_JUSTIFICATION.md 2>/dev/null || true
git commit -m "$1 - stage 1"

echo "Pre-implementation phase done"
echo "####### Pre-implementation phase done. #####" >> hooks.log

claude -p "$(cat <<EOF

Start three code reviewer subagents, to look at the current diff against master.

1. Does an architecture review; results go to PR_ARCHITECTURE_REVIEW_RESULT.md
2. Does an data consistency review; results go to PR_DATA_CONSISTENCY_REVIEW_RESULT.md
3. Does an security review; results go to PR_SECURITY_REVIEW_RESULT.md

EOF
)" --allowedTools "Write"

## By now, we have two new commits in our branch, and some unstaged PR files

claude -p "$(cat <<EOF

Read
- BOYSCOUT_OBSERVATIONS.md
- NEXT_FEATURE.md
- NEXT_FEATURE_JUSTIFICATION.md
- PR_ARCHITECTURE_REVIEW_RESULT.md
- PR_SECURITY_REVIEW_RESULT.md
- PR_DATA_CONSISTENCY_REVIEW_RESULT.md

Then look at the previous to last commit (against master). Its name is "$1 - stage 1"

From the reviews, and all what you know NOW, write
- NEXT_FEATURE_BOYSCOUT_PLAN.md
- NEXT_FEATURE_PROPER_IMPLEMENTATION_PLAN.md.

We will execute the new implementation in two phases, the cleanup phase done by a boyscout, and then the proper implementation.

- Pick mostly high and medium issues from the todos. 
- Be specific which files the implementer should touch on this next, better attempt

EOF
)" --allowedTools "Write"

rm BOYSCOUT_OBSERVATIONS.md
rm NEXT_FEATURE_JUSTIFICATION.md
rm PR_*_RESULT.md

make stop
nohup make start &

if [ ! -f "HUMAN_OPINION.md" ]; then
    read -p "HUMAN_OPINION.md not found. Create it? (y|n): " create_input
    if [ "$create_input" = "y" ]; then
        touch HUMAN_OPINION.md
        $FAVORITE_EDITOR_CMD HUMAN_OPINION.md
    fi
fi

echo "Please human, add your verdict in HUMAN_OPINION.md (note that the app is up at 3027)"

while true; do
    read -p "Type 'ok' to proceed: " input
    if [ "$input" = "ok" ]; then
        break
    fi
done

make stop

## Boyscout phase now

claude -p "$(cat <<EOF

Read
- NEXT_FEATURE_BOYSCOUT_PLAN.md
- HUMAN_OPINION.md

Now implement the feature properly, according to that new plan!

Make sure tests run (`clj -X:test`)

Report what you did in NEXT_FEATURE_BOYSCOUT_HANDOVER.md

EOF
)" --allowedTools "Write"

if ! clj -X:test; then
    echo "Unit tests failed. Aborting."
    exit 1
fi

rm NEXT_FEATURE_BOYSCOUT_PLAN.md

git add .
git reset HEAD -- HUMAN_OPINION.md NEXT_FEATURE_BOYSCOUT_HANDOVER.md NEXT_FEATURE_PROPER_IMPLEMENTATION_PLAN.md 2>/dev/null || true
git commit -m "$1 - boyscout"

## Implementation phase ##

claude -p "$(cat <<EOF

Read
- NEXT_FEATURE_BOYSCOUT_HANDOVER.md
- NEXT_FEATURE_PROPER_IMPLEMENTATION_PLAN.md
- HUMAN_OPINION.md

Now implement the feature properly, according to that new plan!

Make sure tests run (`clj -X:test`)

Report what you did in NEXT_FEATURE_PROPER_IMPLEMENTATION_JUSTIFICATION.md

EOF
)" --allowedTools "Write"

if ! clj -X:test; then
    echo "Unit tests failed. Aborting."
    exit 1
fi

rm NEXT_FEATURE_BOYSCOUT_HANDOVER.md
rm NEXT_FEATURE_PROPER_IMPLEMENTATION_PLAN.md
rm HUMAN_OPINION.md

make stop
nohup make start &

echo "Human, please visit the app in a browser, and also check the changes. Everything ok?"

while true; do
    read -p "Type 'ok' to proceed: " input
    if [ "$input" = "ok" ]; then
        break
    fi
done

## TODO commit and merge etc
