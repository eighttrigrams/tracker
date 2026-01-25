We want a new class of things in the application,

next to Tasks and categories, we want a message entity modelled in the system
which should get its own db table.

It also gets its own top level tab/page, called "Mail", which is positioned after Categories in the navigation.

A message has a sender, a date, a title, and a description, and an associated user (null for admin, like tasks).
(we dont need anything else like other dates other than created_at. we dont need sort order)

One should be able to send messages to the app via API, using the login credentials of users.

As always, when I send a message to a certain user, it gets only to that users mailbox.
An admin should not be able to see other user's messages. And the other way around.
And users should not be able to see each others' messages. All of that should be obvious.

Anyway, when I send a message to the app using a set of credentials, it identifies the user the message should be sent to.

Document a curl command in the README once you are done.
Make sure to test this sufficiently via curl at every implementation step.

## Display

Message should be displayed with cards, like tasks. 
We should be able to mark them done, and then we find them in a Done sort order subtab, like the way it is done for tasks.

## Beta version

We develop the feature for all users, however, only for Admin will we show the new menu item in the navbar.