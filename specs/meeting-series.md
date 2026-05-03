# Feature: Meeting series

## Description

Meeting Series are entities in our system which are separate from Meetings. Meeting Series
group Meetings - there exists a 1:n relationship between a Meeting Series and its Meetings.
Mind you, regular meetings are usually one-off. Only those created as part of a Meeting Series (we'll
discuss the *how* further below) of a Meeting Series are related to a Meeting Series.

In terms of implementation, the 1:n relationship means we don't need a join table. We have merely
a field in Meetings which can be either empty, marking them as one-off, or set to the id of a Meeting Series.

Meeting Series can be seen on the Meetings page/view. In the row with the Heading and the Importance Filter
and Sort Order Selector, as the rightmost group, there is a single button, called simply "Series", that, when
switched **on**, let's us see Meeting Series only, not any invididual Meetings (whether related to a Meeting Series or not).

Meeting Series then behave like regular Entities, they can be searched for with a query string, added (via the Add button),
and filtered by Category Selectors. Meeting Series have neither Importance nor Urgency settings, neither do they time or date by themselves.
When the "Series" button is activated, the Sort Order group and Importance settings disappear accordingly.

## Scheduling

The edit modal for Meeting Series has an additional tab called "Scheduling". In it, we can set a schedule for meetings.
We can check any of the seven days of a week, and select a time (remember a Meeting must always have both a date and a time).
But we also can choose, based on a setting, different times for different days. 
These settings will be used (will be explained later) when spawning off follow up Meetings.

There are two more modes. Both work exclusively with the setting that the time is always the same, not different per day.
- One mode lets you choose a day of the month (up to day 28)
- One mode lets you choose a day of the week, and then runs that on a bi-weekly schedule

## Meets page behaviour

Once a time series has been created, and we are on the Meets page in "Series" mode, each Series card has a filter button, always visible, in its heading line, in the right corner.
Upon pressing that button, we immediately return to normal (non-"Series mode") where Meets, not Series, are listed. But then, instead of the combined search/add input field and button,
we see the name of the Meeting Series just "selected" (filtered by), and we filter only for Meets of that Series, accordingly. And that in both sort orders, Upcoming or Past Meets.
That name of the Series we filter by shown instead the searchbar has an 'X' button, with which we deactivate the filter.

## Today page behaviour

When a Meeting of a Meeting Series appears in the Today section of that page (we ignore the Upcoming section from what we say), and the Meeting Series has no other Meeting
in that series for a **future** day, then in the uncollapsed card for that Meeting item the same blue button appears which let's us create the next Meeting in that Series, according to schedule.
When we click the button and the new Meeting creation went successfully, then we should see a brief notice in a green alert on the top right of the screen (with a fixed positioning, not re-using 
existing widgets) and should disapear after 2 seconds automatically, saying that a Meeting got created. Also, the button should disappear from the original Meeting item immediately after success
has been confirmed (by the backend).

When a Meeting of a Meeting Series appears in the Today section of that page, and the Meeting Series **does have** another Meetingin that series for a **future** day already,
then, and only then, do we show an archive button for that event (for normal Meets, i.e. those not associated with a series, the archive button is always shown). The archive button
marks the Meet as "done for the day" i.e. it will already be shown on the Meets page under the past sort order, where normally only those Meets are which are for a day in the past 
(we are using an :archived property to mark these Meets).

## Automatic next meeting creation

See specs/worker.md
