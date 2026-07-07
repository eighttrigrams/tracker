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

  Scenario: Create-task button creates a belonging task with the entered title
    Given I am on the app
    And an issue "Roof works" exists
    When I reload the page
    And I click the "Issues" tab
    And I click the create-task button on issue "Roof works"
    And I enter "Patch the roof" as the task title
    And I confirm the create-task modal
    And I click the "Tasks" tab
    Then I should see "Patch the roof" in the task list
    And the task "Patch the roof" shows the issue icon

  Scenario: Create-task button opens a title modal, then focuses the issue with the new task
    Given I am on the app
    And an issue "Gutter clean" exists
    When I reload the page
    And I click the "Issues" tab
    And I click the create-task button on issue "Gutter clean"
    And I enter "Clear the downpipe" as the task title
    And I confirm the create-task modal
    Then I should see the issue filter bar for "Gutter clean"
    And I should see the task "Clear the downpipe" in the focused issue task listing

  Scenario: The down-arrow button on an issue card jumps to its task listing
    Given I am on the app
    And a task "Replace tiles" belongs to issue "Roof works"
    When I reload the page
    And I click the "Issues" tab
    And I click the show-tasks button on issue "Roof works"
    Then I should see the issue filter bar for "Roof works"
    And I should see the task "Replace tiles" in the focused issue task listing
    And the task "Replace tiles" in the focused issue task listing is a task card

  Scenario: Done tasks are separated below a divider in the focused issue listing
    Given I am on the app
    And an issue "Roof works" with an open task "Open task" and a completed task "Finished task"
    When I reload the page
    And I click the "Issues" tab
    And I click the show-tasks button on issue "Roof works"
    Then I should see the completed divider in the focused issue task listing
    And the task "Open task" is listed above the completed divider
    And the task "Finished task" is listed under the completed heading

  Scenario: The issue icon on a task jumps to the focused issue task listing
    Given I am on the app
    And a task "Replace tiles" belongs to issue "Roof works"
    When I reload the page
    And I click the "Tasks" tab
    And I click the issue icon on task "Replace tiles"
    Then I should see the issue filter bar for "Roof works"
    And I should see the task "Replace tiles" in the focused issue task listing
    And the task "Replace tiles" in the focused issue task listing is a task card

  Scenario: A created task inherits the currently selected sidebar category
    Given I am on the app
    And an issue "Fence repair" categorised with place "Yard" exists
    When I reload the page
    And I click the "Issues" tab
    And I filter by place "Yard"
    And I click the create-task button on issue "Fence repair"
    And I enter "Fence repair" as the task title
    And I confirm the create-task modal
    And I click the "Tasks" tab
    Then the "Yard" badge on task "Fence repair" should be visible

  Scenario: The Inbox icon navigates to the inbox page
    Given I am on the app
    When I click the Inbox icon
    Then I should see the inbox page
