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
    And a task "First thing" flagged for today exists
    And a task "Second thing" flagged for today exists
    When I navigate to the "Today" tab
    Then the day list reads "Second thing, First thing"
    When I drag the day-list task "Second thing" after the day-list task "First thing"
    Then the day list reads "First thing, Second thing"
    When I click the "Tasks" tab
    And I click the "Manual" sort button
    Then the task list reads "Second thing, First thing"

  Scenario: A task dragged in from Urgent Matters comes in where it was dropped
    Given I am on the app
    And a meet "Team standup" with start date today and time "08:30" exists
    And a meet "Design review" with start date today and time "15:00" exists
    And a task "Fix prod bug" with urgency "urgent" exists
    When I navigate to the "Today" tab
    And I drag the urgent task "Fix prod bug" after the day-list meet "Team standup"
    Then the day list reads "Team standup, Fix prod bug, Design review"
    And "Fix prod bug" is gone from the urgent subsection

  Scenario: An overdue task dropped on the day list still asks about its due date
    Given I am on the app
    And a task "Forgotten chore" with due date yesterday exists
    And a task "Tidy the desk" flagged for today exists
    When I navigate to the "Today" tab
    And I drag the overdue task "Forgotten chore" after the day-list task "Tidy the desk"
    Then a due-date confirmation is asked for
    And the task "Forgotten chore" is still due yesterday
