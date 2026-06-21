Feature: Unified card footer buttons

  Scenario: A standalone footer Delete button uses an 8px corner radius
    Given I am on the app
    And a resource "Footer radius resource" with link "https://example.com" exists
    And I click the "Resources" tab
    When I expand resource "Footer radius resource"
    Then the footer delete button has corner radius "8px"

  Scenario: A footer button without a dropdown has a full border
    Given I am on the app
    And a YouTube inbox message "New clip https://www.youtube.com/watch?v=abc123" exists
    And I click the "Inbox" tab
    When I expand the message "New clip"
    Then the footer convert button has a solid right border
    And the footer convert button has corner radius "8px"

  Scenario: A footer button with a dropdown is a glued 8px group that opens and fires
    Given I am on the app
    And I click the "Tasks" tab
    And I add a task called "Footer dropdown task"
    When I expand task "Footer dropdown task"
    Then the footer main button has left corner radius "8px"
    And the footer dropdown toggle has right corner radius "8px"
    When I open the footer dropdown on task "Footer dropdown task"
    Then I see the footer dropdown menu
    When I click the footer dropdown item "Delete"
    Then I see the delete confirmation
