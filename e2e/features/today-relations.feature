Feature: Today page relations

  Scenario: Relation-mode toggle is visible in the Today tab navbar
    Given I am on the app
    When I navigate to the "Today" tab
    Then the relation-mode toggle should be visible

  Scenario: Linking two tasks from the Today page shows a relation badge on each
    Given I am on the app
    And a task "Today task alpha" with due date today exists
    And a task "Today task beta" with due date today exists
    When I navigate to the "Today" tab
    And I activate relation mode
    And I click the link button on the today item "Today task alpha"
    And I click the link button on the today item "Today task beta"
    Then today item "Today task alpha" should show a relation badge for "Today task beta"
    And today item "Today task beta" should show a relation badge for "Today task alpha"

  Scenario: Picking a source on Resources and navigating to Today preserves the link mode
    Given I am on the app
    And a task "Today task gamma" with due date today exists
    And a resource "Source resource" with link "https://example.com" exists
    When I navigate to the "Resources" tab
    And I activate relation mode
    And I click the link button on the resource "Source resource"
    And I navigate to the "Today" tab
    And I click the link button on the today item "Today task gamma"
    Then today item "Today task gamma" should show a relation badge for "Source resource"
