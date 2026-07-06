Feature: Issues

  Scenario: Issues tab appears in the main navbar and Inbox is a right-side icon
    Given I am on the app
    Then I should see the "Issues" tab in the navbar
    And I should see the Inbox icon

  Scenario: User adds an issue and sees it in the list
    Given I am on the app
    When I click the "Issues" tab
    And I type "Leaky roof" in the issues search field
    And I click the issues add button
    Then I should see "Leaky roof" in the issues list

  Scenario: Create-task button on a collapsed issue card creates a belonging task
    Given I am on the app
    And an issue "Roof works" exists
    When I reload the page
    And I click the "Issues" tab
    And I click the create-task button on issue "Roof works"
    And I click the "Tasks" tab
    Then I should see "Roof works" in the task list
    And the task "Roof works" shows the issue icon

  Scenario: Create-task button focuses the issue and reveals the new task
    Given I am on the app
    And an issue "Gutter clean" exists
    When I reload the page
    And I click the "Issues" tab
    And I click the create-task button on issue "Gutter clean"
    Then I should see the issue filter bar for "Gutter clean"
    And I should see the task "Gutter clean" in the focused issue task listing

  Scenario: The issue icon on a task jumps to the focused issue task listing
    Given I am on the app
    And a task "Replace tiles" belongs to issue "Roof works"
    When I reload the page
    And I click the "Tasks" tab
    And I click the issue icon on task "Replace tiles"
    Then I should see the issue filter bar for "Roof works"
    And I should see the task "Replace tiles" in the focused issue task listing

  Scenario: A created task inherits the currently selected sidebar category
    Given I am on the app
    And an issue "Fence repair" categorised with place "Yard" exists
    When I reload the page
    And I click the "Issues" tab
    And I filter by place "Yard"
    And I click the create-task button on issue "Fence repair"
    And I click the "Tasks" tab
    Then the "Yard" badge on task "Fence repair" should be visible

  Scenario: The Inbox icon navigates to the inbox page
    Given I am on the app
    When I click the Inbox icon
    Then I should see the inbox page
