Feature: Category badges respect the active scope

  Scenario: A work-only category badge is hidden on a card in private scope
    Given I am on the app
    When I click the "Categories" button
    And I click the "Places" category tab
    And I add a category entry called "Office"
    And I expand the card "Office"
    And I set the card "Office" scope to "work"
    And I click the "Back" button
    And I click the "Tasks" tab
    And I add a task called "Fix printer"
    And I assign the place "Office" to task "Fix printer"
    And I switch scope to "work"
    Then task "Fix printer" shows the category badge "Office"
    When I switch scope to "private"
    Then task "Fix printer" does not show the category badge "Office"
    When I switch scope to "work"
    Then task "Fix printer" shows the category badge "Office"
