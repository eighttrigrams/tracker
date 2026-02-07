In the Tasks view, and in the Today view, we have these Task cards,
that when we open them, we have dropdowns for selection of associated
categories by which the tasks can be filtered.

There is a color scheme at work here: Each of the four different category
types has its own color, defined as CSS variables in `resources/public/css/base.css`
under `:root`. Each has a raw RGB triplet and a solid text color:

- `--cat-person-rgb: 52, 199, 89` / `--cat-person-color: #248a3d`
- `--cat-place-rgb: 0, 122, 255` / `--cat-place-color: #0066cc`
- `--cat-project-rgb: 255, 159, 10` / `--cat-project-color: #c77800`
- `--cat-goal-rgb: 255, 69, 58` / `--cat-goal-color: #d70015`

Usage pattern: `background: rgba(var(--cat-<type>-rgb), 0.2); color: var(--cat-<type>-color);`

On the category picker dropdowns in the task cards that is reflected.

On the category badges of the tasks it is reflected, naturally.

But on the left hand side in the sidebar, in the 4 different category type
filter groups, when highlighting, we highlight with that bland blue highlight
color which is used everywhere in the system. I want something more fancy, namely
having the 4 different highlight / badge colours being used here as well.
IMPORTANT: Use `rgba(var(--cat-<type>-rgb), 0.2)` for backgrounds and
`var(--cat-<type>-color)` for text, exactly as the `.tag.<type>` rules do.
Do NOT use the fully saturated base colors as backgrounds — that would be
far too intense. The highlights should be subtle pastels, matching the
existing badge appearance.
That would be so cool and spacey 🚀.

---

One minor fix, unrelated: When visiting the category page, the selected category
subtab (there are 2 of those) is not automatically highlighted (maybe only on first visit, don't know).
Fix that.