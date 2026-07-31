Feature: Work on task

  Scenario: A task due today can be marked as the one being worked on
    Given I am on the app
    And a task "Draft the memo" with due date today exists
    When I navigate to the "Today" tab
    And I expand the today task "Draft the memo"
    And I start working on the today task "Draft the memo"
    Then the today task "Draft the memo" should carry the working-on dot

  Scenario: A task flagged for the day can be marked too
    Given I am on the app
    And a task "Tidy the desk" flagged for today exists
    When I navigate to the "Today" tab
    And I expand the today task "Tidy the desk"
    And I start working on the today task "Tidy the desk"
    Then the today task "Tidy the desk" should carry the working-on dot

  Scenario: Only one task at a time carries the marker
    Given I am on the app
    And a task "First thing" with due date today exists
    And a task "Second thing" with due date today exists
    When I navigate to the "Today" tab
    And I expand the today task "First thing"
    And I start working on the today task "First thing"
    Then the today task "First thing" should carry the working-on dot
    When I expand the today task "Second thing"
    And I start working on the today task "Second thing"
    Then the today task "Second thing" should carry the working-on dot
    And the today task "First thing" should not carry the working-on dot

  Scenario: Stopping work on the task takes the dot away
    Given I am on the app
    And a task "Draft the memo" with due date today exists
    When I navigate to the "Today" tab
    And I expand the today task "Draft the memo"
    And I start working on the today task "Draft the memo"
    Then the today task "Draft the memo" should carry the working-on dot
    When I stop working on the today task "Draft the memo"
    And I open the footer dropdown on the today task "Draft the memo"
    Then the task footer dropdown offers "Work on task"
    And the today task "Draft the memo" should not carry the working-on dot

  Scenario: The marker is server-held and survives a reload
    Given I am on the app
    And a task "Draft the memo" with due date today exists
    When I navigate to the "Today" tab
    And I expand the today task "Draft the memo"
    And I start working on the today task "Draft the memo"
    And I reload the page
    Then the today task "Draft the memo" should carry the working-on dot

  Scenario: Marking the task done takes the marker with it
    Given I am on the app
    And a task "Draft the memo" with due date today exists
    When I navigate to the "Today" tab
    And I expand the today task "Draft the memo"
    And I start working on the today task "Draft the memo"
    Then the today task "Draft the memo" should carry the working-on dot
    When I click the done button on the today task "Draft the memo"
    Then the today section should no longer show "Draft the memo"
    And the working-on API reports no task

  Scenario: The Tasks page does not offer the marker
    Given I am on the app
    And a task "Draft the memo" with due date today exists
    When I click the "Tasks" tab
    And I expand task "Draft the memo"
    And I open the footer dropdown on task "Draft the memo"
    Then the task footer dropdown offers "Delete"
    And the task footer dropdown does not offer "Work on task"

  Scenario: The marker is not offered once the day navigator leaves today
    Given I am on the app
    And a task "Prepare slides" lined up for tomorrow exists
    When I navigate to the "Today" tab
    And I click the second day button
    And I expand the today task "Prepare slides"
    And I open the footer dropdown on the today task "Prepare slides"
    Then the task footer dropdown offers "Unlink"
    And the task footer dropdown does not offer "Work on task"
