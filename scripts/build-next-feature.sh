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

echo "Start building ..."
echo "###### Start building ..." >> hooks.log

claude -p "$(cat <<EOF

We are building a new feature $1.

1. Read the description what to build from build-next-feature/NEXT_FEATURE.md.
2. Implement the feature.
  - if it touches the user interface (which is almost always the case)
    - use a skill
    - take screenshots for proof of important key aspects
3. Make sure you get the unit tests running (`clj -X:test`)    
4. Now that you have an implementation which matches the specification of the new feature handed to you, consider the following: What things did you encounter which caused
  you extra work which wouldnt otherwise have occurred where the code structured in a cleaner way? 
  - Write the cleaner ways down in a build-next-feature/BOYSCOUT_OBSERVATIONS.md 
    - Make sure absolutely nothing specifically pertaining to the new feature goes here. For the purpose of that report, we care about the state of the codebase we found, pretending we dont know what we build next.
5. Explain how what you have done matches what was asked of you. 
    - Write this to build-next-feature/EXPLORATORY_IMPLEMENTATION_DECISIONS_JUSTIFICATION.md
        - If you have taken screenshots, list names of screenshots in this doc (prefix the names with where they are stored, namely .playwright-mcp/)    

EOF
)" --allowedTools "Write"

if ! clj -X:test; then
    echo "Unit tests failed. Aborting."
    exit 1
fi

git add . 
git reset HEAD -- build-next-feature/ 2>/dev/null || true
git commit -m "feature/$1 - Exploratory implementation"

echo "Pre-implementation phase done. Now doing reviews ..."
echo "####### Pre-implementation phase done. Doing reviews now. #####" >> hooks.log

claude -p "$(cat <<EOF

Start three code reviewer subagents, to look at the current diff against master.

1. Does an architecture review; results go to build-next-feature/PR_ARCHITECTURE_REVIEW_RESULT.md
2. Does an data consistency review; results go to build-next-feature/PR_DATA_CONSISTENCY_REVIEW_RESULT.md
3. Does an security review; results go to build-next-feature/PR_SECURITY_REVIEW_RESULT.md

EOF
)" --allowedTools "Write"

required_files=(
    "build-next-feature/BOYSCOUT_OBSERVATIONS.md"
    "build-next-feature/NEXT_FEATURE.md"
    "build-next-feature/EXPLORATORY_IMPLEMENTATION_DECISIONS_JUSTIFICATION.md"
    "build-next-feature/PR_ARCHITECTURE_REVIEW_RESULT.md"
    "build-next-feature/PR_DATA_CONSISTENCY_REVIEW_RESULT.md"
    "build-next-feature/PR_SECURITY_REVIEW_RESULT.md"
)

for file in "${required_files[@]}"; do
    if [ ! -f "$file" ]; then
        echo "Error: Review file $file not found. Aborting."
        exit 1
    fi
    line_count=$(wc -l < "$file" | tr -d ' ')
    if [ "$line_count" -le 1 ]; then
        echo "Error: Review file $file is empty or has only one line. Aborting."
        exit 1
    fi
done

echo "Coming up with a better plan now ..."
echo "### Coming up with a better plan now ..." >> hooks.log

claude -p "$(cat <<EOF

## Preparation

Read
- build-next-feature/BOYSCOUT_OBSERVATIONS.md
- build-next-feature/NEXT_FEATURE.md
- build-next-feature/EXPLORATORY_IMPLEMENTATION_DECISIONS_JUSTIFICATION.md
- build-next-feature/PR_ARCHITECTURE_REVIEW_RESULT.md
- build-next-feature/PR_SECURITY_REVIEW_RESULT.md
- build-next-feature/PR_DATA_CONSISTENCY_REVIEW_RESULT.md

Then look at the previous to last commit (against master). Its name is "feature/$1 - Exploratory implementation"

## High level - what we gonna do

From the reviews, and all what you know NOW, write
- build-next-feature/BOYSCOUT_PLAN.md
- build-next-feature/IMPLEMENTATION_PLAN.md.

We will execute the new implementation in two phases,
1. the cleanup phase done by a boyscout, 
2. and then the proper implementation.

## Info on the plan files to write

### Boyscout plan

Note that soon we will revert the last commit in which we did our exploration.
So the cleanup will be performed against the sources as they appeared in main branch.
Dont put anything specific to our feature in the preparatory boyscout plan. Just make it
nicer for the things which go to the proper implementation. I repeat: Absolutely **nothing
pertaining to the feature** we are about to build goes into the BOYSCOUT_PLAN.md.

### Implementation plan

For the proper implementation, then
- Pick mostly high and medium issues from the todos. 
- Be specific which files the implementer should touch on this next, better attempt

EOF
)" --allowedTools "Write"

rm build-next-feature/BOYSCOUT_OBSERVATIONS.md
rm build-next-feature/EXPLORATORY_IMPLEMENTATION_DECISIONS_JUSTIFICATION.md
rm build-next-feature/PR_*_RESULT.md

make stop
nohup make start &
sleep 2
say "Tracker needs your attention now. Please human, give your opinion."

if [ ! -f "build-next-feature/HUMAN_OPINION.md" ]; then
    read -p "build-next-feature/HUMAN_OPINION.md not found. Create it? (y|n): " create_input
    if [ "$create_input" = "y" ]; then
        touch build-next-feature/HUMAN_OPINION.md
        $FAVORITE_EDITOR_CMD build-next-feature/HUMAN_OPINION.md
    fi
fi

echo "Please human, add your verdict in build-next-feature/HUMAN_OPINION.md (note that the app is up at 3027)"

while true; do
    read -p "Type 'ok' to proceed: " input
    if [ "$input" = "ok" ]; then
        break
    fi
done

make stop
git revert --no-edit HEAD

echo "Starting boyscout refactoring now"
echo "##### Starting boyscout refactoring now" >> hooks.log

## Boyscout phase now

claude -p "$(cat <<EOF

Read
- build-next-feature/BOYSCOUT_PLAN.md
- build-next-feature/HUMAN_OPINION.md

Now implement the feature properly, according to that new plan!

Make sure tests run (`clj -X:test`)

Report what you did in build-next-feature/BOYSCOUT_REPORT.md

EOF
)" --allowedTools "Write"

if ! clj -X:test; then
    echo "Unit tests failed. Aborting."
    exit 1
fi

rm build-next-feature/BOYSCOUT_PLAN.md

git add .
git reset HEAD -- build-next-feature/ 2>/dev/null || true
git commit -m "feature/$1 - Preparatory refactoring"

echo "Starting implentation phase now"
echo "##### Implementation phase starting" >> hooks.log

claude -p "$(cat <<EOF

Read
- build-next-feature/NEXT_FEATURE.md
- build-next-feature/BOYSCOUT_REPORT.md
- build-next-feature/IMPLEMENTATION_PLAN.md
- build-next-feature/HUMAN_OPINION.md

Now implement the feature properly, according to that new plan!
Make sure to use the UI skill to watch your implementation results live in the browser.

Make sure tests run (`clj -X:test`)

Report what you did in build-next-feature/FEATURE_IMPLEMENTATION_DECISIONS_JUSTIFICATION.md

EOF
)" --allowedTools "Write"

if ! clj -X:test; then
    echo "Unit tests failed. Aborting."
    exit 1
fi

rm build-next-feature/BOYSCOUT_REPORT.md
rm build-next-feature/IMPLEMENTATION_PLAN.md
rm build-next-feature/HUMAN_OPINION.md

make stop
nohup make start &

say "Tracker needs your attention now. We are done. Please human, give your opinion."
echo "Human, please visit the app in a browser, and also check the changes. Everything ok?"

while true; do
    read -p "Type 'ok' to proceed: " input
    if [ "$input" = "ok" ]; then
        break
    fi
done

make stop

git add .
git commit -m "feature/$1 - Implementation"

echo "Synthesizing commit message now"

claude -p "$(cat <<EOF

Read
- build-next-feature/NEXT_FEATURE.md
- build-next-feature/FEATURE_IMPLEMENTATION_DECISIONS_JUSTIFICATION.md

Write a commit message body summarizing what was done and why.
Output ONLY the commit message body (no subject line, no markdown fences).
Write the result to build-next-feature/COMMIT_MESSAGE_BODY.txt

EOF
)" --allowedTools "Write"

git commit --amend -m "feature/$1 - Implementation" -m "$(cat build-next-feature/COMMIT_MESSAGE_BODY.txt)"
rm build-next-feature/COMMIT_MESSAGE_BODY.txt
rm build-next-feature/FEATURE_IMPLEMENTATION_DECISIONS_JUSTIFICATION.md

echo "" > build-next-feature/NEXT_FEATURE.md
git add .
git commit --amend --no-edit

echo "Switching back to main now"

git switch main
commit1=$(git rev-parse feature/$1~1)
commit2=$(git rev-parse feature/$1)
git cherry-pick $commit1 $commit2
