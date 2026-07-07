Feature: Issue urgency and urgent matters

  Scenario: Urgent issue appears in the urgent subsection
    Given I am on the app
    And an issue "Leaky roof" with urgency "urgent" exists
    When I navigate to the "Today" tab
    Then I should see the issue "Leaky roof" in the urgent subsection

  Scenario: Superurgent issue appears in the superurgent subsection
    Given I am on the app
    And an issue "Server room flooded" with urgency "superurgent" exists
    When I navigate to the "Today" tab
    Then I should see the issue "Server room flooded" in the superurgent subsection

  Scenario: The urgency picker on an issue card sets its urgency
    Given I am on the app
    And an issue "Broken boiler" exists
    When I click the "Issues" tab
    And I set the urgency of issue "Broken boiler" to superurgent
    And I navigate to the "Today" tab
    Then I should see the issue "Broken boiler" in the superurgent subsection

  Scenario: Dragging an issue from urgent to superurgent changes its urgency
    Given I am on the app
    And an issue "Leaky roof" with urgency "urgent" exists
    When I navigate to the "Today" tab
    And I drag the issue "Leaky roof" from the urgent to the superurgent subsection
    Then I should see the issue "Leaky roof" in the superurgent subsection
    And I should not see the issue "Leaky roof" in the urgent subsection
    When I reload the page
    And I navigate to the "Today" tab
    Then I should see the issue "Leaky roof" in the superurgent subsection

  Scenario: Dragging an issue onto the Today section is rejected
    Given I am on the app
    And an issue "Leaky roof" with urgency "urgent" exists
    When I navigate to the "Today" tab
    And I drag the issue "Leaky roof" onto the due-or-happening section
    Then I should see the issue "Leaky roof" in the urgent subsection
    And I should not see "Leaky roof" in the due-or-happening section
