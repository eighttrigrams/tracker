Feature: Issues

  Scenario: Issues tab appears in the main navbar and Inbox is the last main tab
    Given I am on the app
    Then I should see the "Issues" tab in the navbar
    And I should see the "Inbox" tab in the navbar

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
    And no task in the focused issue task listing shows the belongs-to-issue icon

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

  Scenario: Resolving an issue moves it to the Resolved sort view
    Given I am on the app
    And an issue "Wrap up" exists
    When I reload the page
    And I click the "Issues" tab
    And I expand the issue "Wrap up"
    And I click the resolve button on issue "Wrap up"
    Then I should not see "Wrap up" in the issues list
    When I switch the issues sort to "Resolved"
    Then I should see "Wrap up" in the issues list

  Scenario: An issue with an undone task cannot be resolved
    Given I am on the app
    And a task "Loose end" belongs to issue "Still busy"
    When I reload the page
    And I click the "Issues" tab
    And I expand the issue "Still busy"
    Then the resolve button on issue "Still busy" is disabled

  Scenario: A resolved issue offers no Create Task button
    Given I am on the app
    And a resolved issue "Signed off" exists
    When I reload the page
    And I click the "Issues" tab
    And I switch the issues sort to "Resolved"
    Then the create-task button on issue "Signed off" is not present

  Scenario: Delete works from the issue footer dropdown
    Given I am on the app
    And an issue "Throwaway" exists
    When I reload the page
    And I click the "Issues" tab
    And I expand the issue "Throwaway"
    And I open the footer dropdown on issue "Throwaway"
    And I click the footer dropdown item "Delete"
    And I confirm the issue deletion
    Then I should not see "Throwaway" in the issues list

  Scenario: The footer dropdown does not reopen stale after collapsing and re-expanding a card
    Given I am on the app
    And an issue "Alpha matter" exists
    And an issue "Beta matter" exists
    When I reload the page
    And I click the "Issues" tab
    And I expand the issue "Alpha matter"
    And I open the footer dropdown on issue "Alpha matter"
    Then the footer dropdown on issue "Alpha matter" is open
    When I expand the issue "Beta matter"
    And I expand the issue "Alpha matter"
    Then the footer dropdown on issue "Alpha matter" is closed

  Scenario: The Inbox tab navigates to the inbox page
    Given I am on the app
    When I click the "Inbox" tab
    Then I should see the inbox page
