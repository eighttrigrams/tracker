On the Tasks page, in the manual sort order view, and only in this very view, I am talking about no other view,
on the tasks, there is shown the modified date. In this view, we don't need that. If the task has a due date, on the other hand, that's ok, leave it there,
but take out the modified date.

And then, as far as the dates go, make sure to format them, according to the selected language, wherever you encounter something formatted
yyyy-mm-dd. Don't touch existing logic for selection when to put dates into titles etc. Only where the dates are iso formatted like that,
make it somewhat nicer like 27 Feb 2026 or whatever is appropriate. Centralize this code in some time namespace or something
and make sure we always use this when we need to obtain and format time in our UI.