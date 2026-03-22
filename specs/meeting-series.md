# Feature: Meeting series

Status: Draft

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
These settings will be used (will be explained later) when spawning off follow up Meetings.
