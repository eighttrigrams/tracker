The Today page is the first tab in the navigation bar and the most important page of the app.

It has three sections, vertically
1. Not always shown, the overdue page with tasks that have a due date that is older than today
2. The Day *section*
3. The combined Urgent Matters / Upcoming section

# Sections

## Day section

Above the Day section there is a button group, right aligned, and of the same style as Urgent Matters / Upcoming
or for the different sort orders on the Tasks page.

The first Item is just called "Today", and, assuming Today would be a Wednesday, it has four more buttons, named
after the names of the following four days, which would be "Thursday", "Friday" and so on in our example.

The Day section itself is always divided into two subsections
1. Due or happening today, for tasks with a due date on that day or Meets to happen that day
2. Other things, to line up tasks for that day which are not marked as Urgent, and have no Due Date

We implement only the Due or happening today section. It is straightforward, for a Due Date or a scheduled Meet,
show it in the right day's tab. Dragging and dropping from the Overdue tab into each of the day tabs is fine. In 
any case, it asks for confirmation whether you want to adjust the due date accordingly.

When we have a day selected. Then in the Upcoming section below, should that be open, we only show Meets or Tasks with Due Dates
**after** the selected day. Every time we switch the day, we recalculate the best horizon of the Upcoming section.

# Drag & Drop behaviour

From the overdue section I can drag into any of the days' "Due or happening today" subsections of the Day section, but to nowhere else.
From the Urgent Matters section, I can drag and drop into any of the days' "Other things" subsections, and from there back to Urgent Matters
(bot Super-Urgent and Urgent subsections).