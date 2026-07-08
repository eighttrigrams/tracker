Feature: Category pages

  Scenario: Categories pencil replaces left nav with category tabs
    Given I am on the app
    When I click the "Categories" button
    Then the left nav should show category tabs

  Scenario: People tab shows people as cards
    Given I am on the app
    And a person "Alice" exists
    When I click the "Categories" button
    Then I should see "Alice" in the category cards

  Scenario: Places tab shows places as cards
    Given I am on the app
    And a place "Lisbon" exists
    When I click the "Categories" button
    And I click the "Places" category tab
    Then I should see "Lisbon" in the category cards

  Scenario: Projects tab shows projects as cards
    Given I am on the app
    And a project "Website Redesign" exists
    When I click the "Categories" button
    And I click the "Projects" category tab
    Then I should see "Website Redesign" in the category cards

  Scenario: Goals tab shows goals as cards
    Given I am on the app
    And a goal "Learn Clojure" exists
    When I click the "Categories" button
    And I click the "Goals" category tab
    Then I should see "Learn Clojure" in the category cards

  Scenario: User can add a person from the People page
    Given I am on the app
    When I click the "Categories" button
    And I add a category entry called "Bob"
    Then I should see "Bob" in the category cards

  Scenario: Expanding a card shows the edit pencil button
    Given I am on the app
    And a person "Carol" exists
    When I click the "Categories" button
    And I expand the card "Carol"
    Then the card "Carol" should be expanded
    And I should see the edit pencil button

  Scenario: Pencil button opens the edit modal
    Given I am on the app
    And a person "Dave" exists
    When I click the "Categories" button
    And I expand the card "Dave"
    And I click the edit pencil button
    Then the category edit modal should be open with "Dave"

  Scenario: Reloading from the categories page restores the normal nav
    Given I am on the app
    When I click the "Categories" button
    Then the left nav should show category tabs
    When I reload the page
    Then the left nav should show the normal tabs
