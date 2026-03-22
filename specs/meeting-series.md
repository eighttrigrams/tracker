# Feature: Meeting series

Status: Draft
E2E: doesn't exist yet

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

## Creating new Meetings for Meeting series

In the card for the Meeting Series item, in the buttom left, always visible (not on hover like usually), there is a blue box
button which reads "Create next Meeting". Pressing it creates another Meeting for the series, based on the schedule. The button
is unavailable by default, visible but grayed out; we show the rules for when it is available below.

There are rules for this. First of all we start with the assumption that there can only be at most one Meeting per day for any
given Meeting Series.

When another Meeting for that series exists for today, and no other future Meeting exists, then the button is shown, and when pressing
that button will create the next meeting - based on our schedule.

When no Meeting for that series exists for today, and no other future Meeting exists, 
- and the schedule happens to say that on the day of the week that is today a meeting should happen, then the button is visible and lets us create Today's meeting.
- and today is not a scheduled day, then the button lets us create the next upcoming scheduled meeting.

## Today page behaviour

When a Meeting of a Meeting Series appears in the Today section of that page (we ignore the Upcoming section from what we say), and the Meeting Series has no other Meeting
in that series for a **future** day, then in the uncollapsed card for that Meeting item the same blue button appears which let's us create the next Meeting in that Series, according to schedule.
