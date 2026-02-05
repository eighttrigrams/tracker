bug report.

all categories i selected in the Tasks view, apply only to the Tasks view.
That is, when I select a place from the Tasks view, and visit the Today
page, it does not matter what categories (here a place) I selected *on the Tasks page*

When going to the Today page, it should look up in the state corresponding to the Today page
which categories are selected **there**. So the two pages have separated states.

Maybe we should follow the pattern we have set a precenent for with the Mail page, namely
that each page gets its own state atom.

Maybe in the same go:

We have just implemented that on the Tasks page filtering is done *in the backend* (like it should be).
I noticed we didn't do that for the Today page. The behaviour, as we know, on the Today page, is *filtering things out**
not filtering ***for*** things. But you know that, by looking at the frontend rules. Carry those in the backend,
make sure we have tests in place.

And again, on page switch, especially between Tasks and Today pages, we fetch and filter, based on the individual tab's state.