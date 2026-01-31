filter by categories in the backend, pass a new query (list) param categories (names!) to the backend

---

multi-prefix search for filter categories in the left hand sides category pickers

---

DONE

Now for something rather complicated, or tricky, intricate.

Concerns: The Private | Both | Work switcher at the top (not the one corresponding selector in the task cards; leave that one alone, as it is right now).

Ok, what we gonna do is mostly optics. Instead the word "Both" I want to have a horizontal vesicapiscis, i.e. two intersecting circles next to each other,
side by side, with a bit in the middle where they intersect. In normal (not inverted, where inverted was previously symbolized by the exclamation mark),
this means when I select middle (where the vesicapiscis is), we see all tasks, private, those marked as both, and work tasks. When we select Private
in the vesicapiscis symbol the left side and the intersection is highlighted, when I select Work, then in the vesicapiscis the right side and the intesection is highlighted.

Of course we have to take into account that when we are in Both, i.e. vesicapiscis is selected, that button is blue, therefore the selection then is
white, and the circle outlines are white, whereas when we select Work or Private, then either of those is blue, leaving the vesicapiscis button white,
which means the outline there is now blue and the highlighting also blue.

Now, when I click Both, and only when I already am on both and click it again, it switches to inverted mode, which in this case means intersection mode
(formerly highlighted by exclamation mark). Now, when I switch to Work or Both or Private, the highlighting should only be the left circle minus the intersection
the intersection itself, or the right circle minus the intersection. The same highlighting rules as outlined in the paragraph above apply. We always
must make sure the user can see blue on white or white on blue.

Now, the color scheme is exactly inverted again, when we are in dark mode. Whatever mode we are in and whichever button is selected, the highlight vs background color
is then the opposite.