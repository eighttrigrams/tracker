I want to have multi-prefix search, like we have for tasks, 
also for the category selector widgets which are shown on the left hand side in the Today and Tasks views
and in the category search on the category pages.

I.e. when I search for "a bb", I would match "abc bbab blablub". You can see the rules from what we do for tasks.

I am aware that this here is frontend-only filtering, no worries.
But we can put the pure logic into *.cljc files for easier unit testing.