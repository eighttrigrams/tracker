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

If there is a Meet scheduled for Today but no Meet exist, create that.

And now two mutually exclusive cases where Meet creation can happen.

If there is another Meet scheduled between Tomorrow and Today+4 (inclusive) but there are Meets missing,
create them, but only for those days after the last day for which they do exist. That is, if our schedule
says every day should be a meet, but only for Today+2 exists one, ignore everything before that and create 
one for Today+3 and one for Today+4.

If there is only one Meet scheduled between Today - Today+4 (inclusive), make sure that is created, whatever it is.
And then create one additional Meet after Today+4.
