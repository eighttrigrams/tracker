Implement full text search with multiple prefixes possible for Tracker.

I.e. when I have the following list
1. aaaba cccde
2. kkml aka
3. aaa alba

Then the search term "aaa" matches 1 and 3.
And the search term "aaa al" matches 3.

This should apply to the tasks search only, not for when i select categories to filter by in the category picker.

This is a backend heavy feature. Also a feature which involves the particular ways the sqlite handles things,
so add tests which involve the db.

---

I also think, and this may be something for the preparatory refactoring, we should introduce and use honey sql
instead of sticking strings together for the query