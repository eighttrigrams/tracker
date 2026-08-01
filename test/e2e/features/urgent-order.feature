Feature: Urgent Matters has an order of its own

  Scenario: Reordering a task in Urgent Matters leaves the Tasks page order alone
    Given I am on the app
    And a task "Alpha" with urgency "urgent" exists
    And a task "Beta" with urgency "urgent" exists
    And a task "Gamma" with urgency "urgent" exists
    When I navigate to the "Today" tab
    Then the urgent task list reads "Gamma, Beta, Alpha"
    When I click the "Tasks" tab
    And I click the "Manual" sort button
    Then the task list reads "Gamma, Beta, Alpha"
    When I click the "Today" tab
    And I drag the urgent task "Alpha" before the urgent task "Gamma"
    Then the urgent task list reads "Alpha, Gamma, Beta"
    When I click the "Tasks" tab
    And I click the "Manual" sort button
    Then the task list reads "Gamma, Beta, Alpha"
    When I drag the task "Beta" before the task "Gamma"
    Then the task list reads "Beta, Gamma, Alpha"
    When I click the "Today" tab
    Then the urgent task list reads "Alpha, Gamma, Beta"

  # Dropped into the middle on purpose: at the end of the block the position the
  # drop asks for and the position a stray second write would give it are the
  # same, and the drag that used to race itself would pass anyway.
  Scenario: A task dragged across the urgency blocks lands where it was dropped
    Given I am on the app
    And a task "Super A" with urgency "superurgent" exists
    And a task "Super B" with urgency "superurgent" exists
    And a task "Super C" with urgency "superurgent" exists
    And a task "Dragged" with urgency "urgent" exists
    When I navigate to the "Today" tab
    Then the superurgent task list reads "Super C, Super B, Super A"
    And the urgent task list reads "Dragged"
    When I drag the urgent task "Dragged" after the superurgent task "Super C"
    Then the drop made exactly one write
    And the superurgent task list reads "Super C, Dragged, Super B, Super A"
    And the urgent task list is empty

  Scenario: Reordering an issue in Urgent Matters leaves the Issues page order alone
    Given I am on the app
    And an issue "Roof" with urgency "urgent" exists
    And an issue "Hedge" with urgency "urgent" exists
    And an issue "Boiler" with urgency "urgent" exists
    When I navigate to the "Today" tab
    Then the urgent issue list reads "Boiler, Hedge, Roof"
    When I click the "Issues" tab
    And I click the manual sort button on the Issues page
    Then the issue list reads "Boiler, Hedge, Roof"
    When I click the "Today" tab
    And I drag the urgent issue "Roof" before the urgent issue "Boiler"
    Then the urgent issue list reads "Roof, Boiler, Hedge"
    When I click the "Issues" tab
    And I click the manual sort button on the Issues page
    Then the issue list reads "Boiler, Hedge, Roof"
