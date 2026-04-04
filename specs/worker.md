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

These work differently. Only one next Task gets created. If Today is scheduled, but no Tasks exists yet, then create it, otherwise
create the next one, whenever that is according to schedule, but only if it is between Today and Today+4 (inclusive).

One important note here though, to prevent recreation of the same task over and over again today. Tasks have a date to mark *when* we have
marked them as done. If an item exists of the series, which has been marked as done **today**, ignore that one Task 
(or all such Tasks of the series marked as today done) and create another for after Today (up to maximum Today+4, inclusive).
