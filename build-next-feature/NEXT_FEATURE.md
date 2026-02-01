We recently implemented multi-prefix search for task names/titles on the Task page.
Since we don't implement full text search over descriptions (neither do I want that),
I want to provide an additional means to users to search for additional search terms to match.

These should come in the form of a free text field, edited in the same spot that we edit
description and title/name (when we hit the pencil), and HOW it is stored and my feature is implemented
does not interest me much other than it should be efficient. But for the user
it should be presented as a simple input field where we can enter some additional text, separated by whitespace.
For example "abc bbd aad term1 term2".

Search then works like this:

Lets say our db contains tasks with titltes like this:

- "aaaba bbbd kka"
- "eieie yoyo"
- "aaa mmm"

The existing behaviour says that a ("multi-prefix") search term of "aa bb"
hits "aaaba bbbd kka" but not "aaa mmm".

Now our addition, lets imagine tasks like this, as defined by titles and additional tags

- "aaaba bbbd kka" | "cool"
- "eieie yoyo" | "sup"
- "aaa mmm" | "alma bb"

Now a search for "aa bb" hits

- "aaaba bbbd kka" | "cool"
- "aaa mmm" | "alma bb"
