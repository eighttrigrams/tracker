Feature: Right-clicking an item card opens its footer menu

  Scenario: A collapsed card offers the footer menu, and an entry from it fires
    Given I am on the app
    And I click the "Tasks" tab
    And I add a task called "Context menu task"
    When I right-click the card "Context menu task"
    Then the card menu offers "Mark task done, Set Reminder, Delete"
    When I click the card menu entry "Delete"
    Then I see the delete confirmation

  Scenario: An expanded card offers the same menu
    Given I am on the app
    And I click the "Tasks" tab
    And I add a task called "Context menu task"
    And I expand the task card "Context menu task"
    When I right-click the card "Context menu task"
    Then the card menu offers "Mark task done, Set Reminder, Delete"

  Scenario: Escape and a click elsewhere close the menu
    Given I am on the app
    And I click the "Tasks" tab
    And I add a task called "Context menu task"
    When I right-click the card "Context menu task"
    Then the card menu offers "Mark task done, Set Reminder, Delete"
    When I press Escape
    Then no card menu is open
    When I right-click the card "Context menu task"
    Then the card menu offers "Mark task done, Set Reminder, Delete"
    When I click outside the card menu
    Then no card menu is open

  Scenario: Outside a card the browser keeps its own menu
    Given I am on the app
    And I click the "Tasks" tab
    And I add a task called "Context menu task"
    Then a right-click on the card "Context menu task" is taken over by the app
    And a right-click on the page background is left to the browser

  Scenario: A resource with a link offers to copy it, one without does not
    Given I am on the app
    And a resource "Linked resource" with link "https://example.com/linked" exists
    And a resource "Sheetish resource" without a link exists
    And I click the "Resources" tab
    When I right-click the card "Sheetish resource"
    Then the card menu offers "Delete"
    When I right-click the card "Linked resource"
    Then the card menu offers "Delete, Copy link"
    When I click the card menu entry "Copy link"
    Then the copy signal flashes
    And the clipboard holds "https://example.com/linked"
