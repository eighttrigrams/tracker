In the Tasks view, and in the Today view, we have these Task cards,
that when we open them, we have dropdowns for selection of associated
categories by which the tasks can be filtered.

There is a color scheme at work here: Each of the four different category
types has its own color, defined in `resources/public/styles.css` as `.tag.<type>` rules (lines 384-387).
Each uses a base RGB color at 20% alpha for the background, with a darker
solid variant for the text:

- person: `rgba(52, 199, 89, 0.2)` / `#248a3d`
- place: `rgba(0, 122, 255, 0.2)` / `#0066cc`
- project: `rgba(255, 159, 10, 0.2)` / `#c77800`
- goal: `rgba(255, 69, 58, 0.2)` / `#d70015`

On the category picker dropdowns in the task cards that is reflected.

On the category badges of the tasks it is reflected, naturally.

But on the left hand side in the sidebar, in the 4 different category type
filter groups, when highlighting, we highlight with that bland blue highlight 
color which is used everywhere in the system. I want something more fancy, namely
having the 4 different highlight / badge colours being used here as well.
IMPORTANT: Use the same 20% alpha (`rgba(..., 0.2)`) backgrounds with the
darker solid text colors, exactly as the `.tag.<type>` rules do. Do NOT use
the fully saturated base colors (e.g. `rgb(0, 122, 255)`) as backgrounds —
that would be far too intense. The highlights should be subtle pastels,
matching the existing badge appearance.
That would be so cool and spacey 🚀.

---

One minor fix, unrelated: When visiting the category page, the selected category
subtab (there are 2 of those) is not automatically highlighted (maybe only on first visit, don't know).
Fix that.