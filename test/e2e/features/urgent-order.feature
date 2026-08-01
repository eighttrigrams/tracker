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
