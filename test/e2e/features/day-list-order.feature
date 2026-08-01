Feature: One drag-orderable list per day

  Scenario: The day list merges what is due with what is flagged, and ends with the add button
    Given I am on the app
    And a meet "Team standup" with start date today and time "08:30" exists
    And a meet "Design review" with start date today and time "15:00" exists
    And a task "Draft the memo" with due date today exists
    And a task "Tidy the desk" flagged for today exists
    When I navigate to the "Today" tab
    Then the day list reads "Draft the memo, Team standup, Design review, Tidy the desk"
    And the day list has one heading
    And the add button is the last thing in the day list

  Scenario: The add button appends to the end of the list
    Given I am on the app
    And a meet "Team standup" with start date today and time "08:30" exists
    And a task "Tidy the desk" flagged for today exists
    When I navigate to the "Today" tab
    And I add a task "Water the plants" via the today add button
    Then the day list reads "Team standup, Tidy the desk, Water the plants"
    When I add a task "Call the bank" via the today add button
    Then the day list reads "Team standup, Tidy the desk, Water the plants, Call the bank"

  Scenario: Only tasks can be picked up
    Given I am on the app
    And a meet "Team standup" with start date today and time "08:30" exists
    And a task "Tidy the desk" flagged for today exists
    When I navigate to the "Today" tab
    Then the day-list task "Tidy the desk" is draggable
    And the day-list meet "Team standup" is not draggable

  Scenario: A task dropped onto a meet lands next to it
    Given I am on the app
    And a meet "Team standup" with start date today and time "08:30" exists
    And a meet "Design review" with start date today and time "15:00" exists
    And a task "Tidy the desk" flagged for today exists
    When I navigate to the "Today" tab
    And I drag the day-list task "Tidy the desk" after the day-list meet "Team standup"
    Then the day list reads "Team standup, Tidy the desk, Design review"

  Scenario: Reordering a task that is due today leaves its due date alone
    Given I am on the app
    And a task "Draft the memo" with due date today exists
    And a task "Tidy the desk" flagged for today exists
    When I navigate to the "Today" tab
    And I drag the day-list task "Draft the memo" after the day-list task "Tidy the desk"
    Then the day list reads "Tidy the desk, Draft the memo"
    And no due-date confirmation is asked for
    And the task "Draft the memo" is still due today

  Scenario: Dropping a task of this day beside the list is not a change of day
    Given I am on the app
    And a task "Draft the memo" with due date today exists
    And a task "Tidy the desk" flagged for today exists
    When I navigate to the "Today" tab
    And I drop the day-list task "Draft the memo" beside the day list heading
    Then no due-date confirmation is asked for
    And the task "Draft the memo" is still due today
    And the day list reads "Draft the memo, Tidy the desk"

  Scenario: The day list order is kept apart from the Tasks page order
    Given I am on the app
    And a task "Alpha" flagged for today exists
    And a task "Beta" flagged for today exists
    And a task "Gamma" flagged for today exists
    When I navigate to the "Today" tab
    Then the day list reads "Alpha, Beta, Gamma"
    When I click the "Tasks" tab
    And I click the "Manual" sort button
    Then the task list reads "Gamma, Beta, Alpha"
    When I click the "Today" tab
    And I drag the day-list task "Gamma" before the day-list task "Alpha"
    Then the day list reads "Gamma, Alpha, Beta"
    When I click the "Tasks" tab
    And I click the "Manual" sort button
    Then the task list reads "Gamma, Beta, Alpha"
    When I drag the task "Alpha" before the task "Gamma"
    Then the task list reads "Alpha, Gamma, Beta"
    When I click the "Today" tab
    Then the day list reads "Gamma, Alpha, Beta"

  Scenario: A task dragged in from Urgent Matters comes in where it was dropped
    Given I am on the app
    And a meet "Team standup" with start date today and time "08:30" exists
    And a meet "Design review" with start date today and time "15:00" exists
    And a task "Fix prod bug" with urgency "urgent" exists
    When I navigate to the "Today" tab
    And I drag the urgent task "Fix prod bug" after the day-list meet "Team standup"
    Then the day list reads "Team standup, Fix prod bug, Design review"
    And "Fix prod bug" is gone from the urgent subsection

  # An overdue task is in no day list whatever it is dropped on, so a drop on a
  # day-list card must not give it a place there — it used to, and the card
  # appeared in the day list until the refetch took it away again. The reminder
  # is what makes the card reach the item-level drop at all.
  Scenario: An overdue task carrying a reminder cannot take a place in the day list
    Given I am on the app
    And a task "Forgotten chore" overdue with an active reminder exists
    And a task "Tidy the desk" flagged for today exists
    When I navigate to the "Today" tab
    And I click the "Reminders" view switcher button
    And I drag the reminder task "Forgotten chore" onto the day-list task "Tidy the desk"
    Then the task "Forgotten chore" has no reminder left
    And the task "Forgotten chore" has no place in a day list
    And the task "Forgotten chore" is still due yesterday

  Scenario: An overdue task dropped on the day list still asks about its due date
    Given I am on the app
    And a task "Forgotten chore" with due date yesterday exists
    And a task "Tidy the desk" flagged for today exists
    When I navigate to the "Today" tab
    And I drag the overdue task "Forgotten chore" after the day-list task "Tidy the desk"
    Then a due-date confirmation is asked for
    And the task "Forgotten chore" is still due yesterday
