# Feature: Recurring tasks

## Description

Recurring Tasks are entities in our system which are separate from Task. Recurring Tasks
group Tasks - there exists a 1:n relationship between a Recurring Task and its Tasks.
Mind you, regular tasks are usually one-off. Only those created as part of a Recurring Task (we'll
discuss the *how* further below) of a Recurring Task are related to a Recurring Task.

In terms of implementation, the 1:n relationship means we don't need a join table. We have merely
a field in Tasks which can be either empty, marking them as one-off, or set to the id of a Recurring Task.

Recurring Tasks can be seen on the Tasks page/view. In the row with the Heading and the Importance Filter
and Sort Order Selector, as the rightmost group, there is a single button, called simply "Recurring", that, when
switched **on**, let's us see Recurring Tasks only, not any invididual Tasks (whether related to a Recurring Tasks or not).

Recurring Tasks then behave like regular Entities, they can be searched for with a query string, added (via the Add button),
and filtered by Category Selectors. Recurring Tasks have neither Importance nor Urgency settings, neither do they time or date by themselves.
When the "Recurring" button is activated, the Sort Order group and Importance settings disappear accordingly.

## Scheduling

The edit modal for Recurring Tasks has an additional tab called "Scheduling". In it, we can set a schedule for tasks.
We can check any of the seven days of a week.
These settings will be used (will be explained later) when spawning off follow up (recurring) Tasks.

There are two more modes.
- One mode lets you choose a day of the month (up to day 28)
- One mode lets you choose a day of the week, and then runs that on a bi-weekly schedule

There are two modes (selectable by radio button on the scheduling tab of the edit modal) for the "types" of (recurring) Tasks we create
- The tasks has a due date and therefore is expected to appear on the due date in the "Due or happening today" sub-section of the Today section of the Today page. Here, for the creation of any follow up Task of a Recurring Task, it does not matter whether an overdue task exists, the next one gets created when the schedule says.
- The task has no due date and is expected to appear in the "Other things" sub-section of the Today section of the Today page. When a Task of that Recurring Task already exists in that section, we won't create a follow up despite the schedule possibly saying so.

On the second case: When we have a Task that has the `:today` marker and is not archived, then no new Task of that type. If we have a Task marked with `:today`, and already set archived, we won't allow a new Task
to be created today.

## Automatic next meeting creation

Every hour a process runs which checks whether a future (or today, in case one doesn't exist for today but should, according to schedule) Task exists
with a due date according to schedule. If not, it creates **one**.
