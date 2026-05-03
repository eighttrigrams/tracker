The worker currently creates Meets for Meeting Series and Tasks for Recurring Tasks.

## Meeting Series

At each worker run, the worker goes through all Meeting Series items for all users.
For an individual Meeting Series, it is determined whether individual Meets will be generated.
The rules are as follows:

Past meetings (from any day earlier than today) won't be considered at all.

We are always interested especially in the following days
- Today 
- Tomorrow 
- Today+2 
- Today+3 
- Today+4

which are tracked by the :today and the :lined_up_for properties.

If there already exists a Meet after Today+4 for the Meeting Series, do nothing.

And now two mutually exclusive cases where Meet creation can happen.

If there is another Meet scheduled between Tomorrow and Today+4 (inclusive) but there are Meets missing,
create them, but only for those days after the last day for which they do exist. That is, if our schedule
says every day should be a meet, but only for Today+2 exists one, ignore everything before that and create
one for Today+3 and one for Today+4. If Today is also scheduled but missing, only create it if there are
no existing Meets between Tomorrow and Today+4.

If there is only one Meet scheduled between Today - Today+4 (inclusive), make sure that is created, whatever it is.
And then create one additional Meet after Today+4.

## Recurring Tasks

There are two types of Recurring Tasks
1. Those with a Due Date
2. Those without a Due Date

### Recurring Tasks with Due Date

Recurring Tasks with Due Dates function the according to the same rules as creation of Meets from Meeting Series.
Importantly, to determine which dates are already "taken", we consider both Tasks not marked as done as well as those marked as done.

### Recurring Tasks without Due Date

These work differently. At most one upcoming Task gets created per series, and only if its scheduled date falls within a **Today to Today+4** window (inclusive).

- If no undone Task of the series exists from Today onward, create the next scheduled one (within the window).
- If an undone Task already exists, do nothing.
- **Done-today exception:** If all existing Tasks of the series were marked as done *today*, treat the series as having no active Task and create the next one after Today (still within the Today+4 window). This prevents the worker from re-creating the same task repeatedly on the same day.

A special situation occurs when I **delete** a Task of the Series marked for *today*. Then the next job run would recreate it. In that case, when I delete it,
don't wait for the worker to kick in. Instead, create the next item of the series in that moment of deletion, but *only when it falls between tomorrow and today+4 (both inclusive).
