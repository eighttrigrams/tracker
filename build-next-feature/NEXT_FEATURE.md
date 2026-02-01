On the Today page, in contrast to the Tasks page,
we can't edit the title or the description of tasks.

Fix that.

But one thing to note here, on the Today page, in contrast to the tasks page, we don't show the additional tags field.
Make sure to not null or empty it when it contains something. Simply don't show it on the Today page's tasks cards edit sections.

---

Also, we can change context and importance, but not urgency.

Fix that.

---

Minor thing, to sneak into this changeset:

When we are not in a category picker's input field, but whereever else on the Tasks page, and press option+escape, which
removes all category selections, then also make sure this collapses any potential uncollapsed (open) task cards.