Implement full text search with multiple prefixes possible for Tracker.

I.e. when I have the following list
1. aaaba cccde
2. kkml aka
3. aaa alba

Then the search term "aaa" matches 1 and 3.
And the search term "aaa al" matches 3.

This should apply to the tasks search as well as for the search in the category selector widgets everywhere.

Consider extracting common code into reused functions.
This is a backend heavy feature. Also a feature which involves the particular ways the sqlite handles things,
so add tests which involve the db.