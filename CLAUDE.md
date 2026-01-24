# Introducing new features to Tracker

Whenever you are instructed to merge a feature into main/master
- You run the unit tests and see whether they are fine
- You replicate what we have built in the browser and demonstrate it, keeping the browser open at the end and explaining to the human how the human can verify for himself
- You ask human if he has performed a review or whether you should do one (if you haven't been asked earlier)
- You do a squash merge of the current feature branch into main
    - See if there are unrelated changes/commits. If yes, cherry-pick them INTO main first.
    - From the squash merge, the PR.md and PR_<aspect>_REVIEW_RESULT.md files should be excluded but contents, specially of PR.md, should go to the extended git commit message
    - The important bit is that I want to have the feature related work in a single commit, with a single commit message, for future reference
