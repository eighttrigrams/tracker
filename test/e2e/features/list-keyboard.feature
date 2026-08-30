Feature: Escape and the save combo on a list page

  Escape, with the cursor outside the search box, closes the card that is open
  and puts the cursor in the box. The save combo, with the cursor in the box,
  enacts Add — the same thing Enter does there.

  Scenario: Escape closes the open card and puts the cursor in the search box
    Given I am on the app
    And an issue "Leaky roof" exists
    When I reload the page
    And I click the "Issues" tab
    And I expand the issue "Leaky roof"
    Then the issue "Leaky roof" should be expanded
    When I click away from the search box
    And I press Escape
    Then the issue "Leaky roof" should be collapsed
    And the issues search field should have focus

  Scenario: Escape with nothing open leaves the page alone
    Given I am on the app
    And an issue "Nothing open" exists
    When I reload the page
    And I click the "Issues" tab
    And I click away from the search box
    And I press Escape
    Then the issue "Nothing open" should be collapsed
    And I should see "Nothing open" in the issues list

  Scenario: Escape in the search box still clears the search rather than closing a card
    Given I am on the app
    And an issue "Still typing" exists
    When I reload the page
    And I click the "Issues" tab
    And I expand the issue "Still typing"
    And I type "Still" in the issues search field
    And I focus the issues search field
    And I press Escape
    Then the issues search field should be empty

  Scenario: The save combo in the search box adds the issue
    Given I am on the app
    When I click the "Issues" tab
    And I type "Added by the save combo" in the issues search field
    And I focus the issues search field
    And I press the save combo
    Then I should see "Added by the save combo" in the issues list
    And the issues search field should be empty

  Scenario: The save combo in the tasks search box adds the task
    Given I am on the app
    When I click the "Tasks" tab
    And I type "Task by the save combo" in the search field
    And I focus the tasks search field
    And I press the save combo
    Then the tasks search field should be empty
    And I should see "Task by the save combo" in the task list
