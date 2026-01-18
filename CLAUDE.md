# Introducing new features to Tracker

Whenever you are instructed to work on a new feature, 
- make sure we do this in a dedicated branch.
- keep track of the current feature, inside the branch, in a `PR.md`, which you keep up to date
- make sure you have a browser open such that you can see the feature for yourself, as the human would, (make use of playwright)

Whenever you are instructed to do a PR review of the current feature, 
- when not explicitly mentioned, we don't do a **full** review, our philosophy is to focus on an iterative cadence and focus on what has been added
    - what has been added is entangled with existing stuff so in aggregate we expect this to cover everything, without on every review explicitly covering everything
- invoke a reviewer agent and tell it 
    - to look at the current changeset 
        - and describe it to the agent how the boundaries of the changeset are defined
            - could be all what is different from master, with a little bit of surrounding context, 
            - and functions called from the changed code or calling the changed code)
    - to do an architecture review
        - to write its results to PR_ARCHITECTURE_REVIEW_RESULT.md
    - to do an security review
        - to write its results to PR_SECURITY_REVIEW_RESULT.md
    - to do an data consistency review
        - to write its results to PR_DATA_CONSISTENCY_REVIEW_RESULT.md    
- then read the various PR_<aspect>_REVIEW_RESULT.md, propose changes to the human and ask it whether you should implement on them
    - if the human answers yes to any of those, first commit the changes in the branch to checkpoint, then move on
    - consider creating GitHub issues for non-critical items, after a merge (see below), link from the issues to the commit from which they spawned

Whenever you are instructed to merge a feature into main/master
- You run the unit tests and see whether they are fine
- You replicate what we have built in the browser and demonstrate it, keeping the browser open at the end and explaining to the human how the human can verify for himself
- You ask human if he has performed a review or whether you should do one (if you haven't been asked earlier)
- You do a squash merge of the current feature branch into main
    - See if there are unrelated changes/commits. If yes, cherry-pick them INTO main first.
    - From the squash merge, the PR.md and PR_<aspect>_REVIEW_RESULT.md files should be excluded but contents, specially of PR.md, should go to the extended git commit message
    - The important bit is that I want to have the feature related work in a single commit, with a single commit message, for future reference
