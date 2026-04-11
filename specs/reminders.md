# Feature: Reminders

Tasks have on their task cards, in the open/uncollapsed card at the bottom right where the dropdown menu is
a new option, set reminder. Upon clicking that, we enter a modal, where we can select a date for a reminder.

Our worker checks whether that date arrived. If that is the case, it stores a **reminder** property and sets it to ***active***.
When a reminder is set to active, the worker sets the modified date (once) to the date when the worker performed that action.

Furthermore, as far as the abovementioned dropdown goes. As long as the reminder is active, there is only one button and no dropdown.
The button says "Acknowledge Reminder". When pressing that button, the reminder property gets unset, and the button/dropdown combination 
works as before.

## Today page behaviour

The group "Urgent Matters"/"Upcoming" gets a third item "Reminders", which shows all Tasks we have currently active reminders for.
Have that group have a little red icon when that subtab is not selected and that list is NOT empty.
