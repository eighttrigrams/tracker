Whenever you are instructed to work on a new feature, make sure we do this in a dedicated branch.

Whenever you are instructed to do a PR review of the current feature, 
- invoke a reviewer agent and tell it 
    - to look at the current changeset 
        - describe it to the agent how the boundaries of the changeset are defined
            - could be all what is different from master, with a little bit of surrounding context, 
            - and functions called from the changed code or calling the changed code)
    - to do an architecture review
    - to write its results to PR_REVIEW.md
- then read PR_REVIEW.md, propose changes to the human and ask it whether you should implement on them
