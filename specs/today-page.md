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

### Due or happening today subsection

This is straightforward, for a Due Date or a scheduled Meet,
show it in the right day's tab. Dragging and dropping from the Overdue tab into each of the day tabs is fine. In 
any case, it asks for confirmation whether you want to adjust the due date accordingly.

When we have a day selected. Then in the Upcoming section below, should that be open, we only show Meets or Tasks with Due Dates
**after** the selected day. Every time we switch the day, we recalculate the best horizon of the Upcoming section.

### Other things section

On every day, I can add things via the plus button. Or drag and drop between the Urgent Matters and the Other things subsection.
When I add something to one of the upcoming days in the Day section under Other things, the Task gets marked either with `:today`
if it's for today, or with a property `:lined_up_for` (which has ISO dates as values). And then tasks get associated to the specific
days. We then have a worker process (which we already use for Recurring Tasks and Meeting Series) which checks regularly, and if we 
have a task which is lined up for the day which is actually today, we "nil" that property and set `:today` to true/1, which means that
tasks for actual today accumulate there.

# Drag & Drop behaviour

From the overdue section I can drag into any of the days' "Due or happening today" subsections of the Day section, but to nowhere else.

From the Urgent Matters section, I can drag and drop into any of the days' "Other things" subsections, and from there back to Urgent Matters
(bot Super-Urgent and Urgent subsections).

As for the Days section. I can drag and drop from the individual days' "Other things" subsections into the Urgent Matters sections, and from
the Urgent Matters to *any of the days' "Other things" subsections. But I can also drag from any day's "Other things" to the other day buttons, 
such that a Task so dragged and dropped will appear in the target day's "Other things" section. As drop targets, only the available days are marked.