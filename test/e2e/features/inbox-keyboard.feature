Feature: The save combo in the Inbox add box

  The Inbox add box is not one of the combined search-add bars — it only adds,
  and it is governed by a disabled? that also covers an active filter. The save
  combo enacts Add there too.

  Scenario: The save combo creates the note
    Given I am on the app
    When I click the "Inbox" tab
    And I type "Note by the save combo" in the inbox add box
    And I press the save combo in the inbox
    Then the inbox add box should be empty
    And I should see "Note by the save combo" in the message list

  Scenario: Enter still creates the note
    Given I am on the app
    When I click the "Inbox" tab
    And I type "Note by Enter" in the inbox add box
    And I press Enter in the inbox add box
    Then the inbox add box should be empty
    And I should see "Note by Enter" in the message list

  Scenario: The save combo adds nothing from an empty add box
    Given I am on the app
    When I click the "Inbox" tab
    And I press the save combo in the inbox, expecting nothing
    Then the inbox add box should be empty
    And the message list should be empty
