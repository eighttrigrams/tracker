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

  Scenario: Back button returns from categories to the previous main view
    Given I am on the app
    When I click the "Tasks" tab
    And I click the "Categories" button
    Then the left nav should show category tabs
    And I should see the categories back button
    When I click the "Back" button
    Then the left nav should show the normal tabs
    And the "Tasks" tab should be active

  Scenario: Back button does not appear on normal pages
    Given I am on the app
    When I click the "Tasks" tab
    Then I should not see the categories back button

  Scenario: The scope switcher is visible on category pages
    Given I am on the app
    When I click the "Categories" button
    Then I should see the scope switcher

  Scenario: A category set to work scope is hidden when viewing private scope
    Given I am on the app
    And a person "Alice" exists
    When I click the "Categories" button
    And I expand the card "Alice"
    And I set the card "Alice" scope to "work"
    And I switch scope to "private"
    Then I should not see "Alice" in the category cards
    When I switch scope to "work"
    Then I should see "Alice" in the category cards

  Scenario: The save combo in the add box creates the category entry
    Given I am on the app
    When I click the "Categories" button
    And I type "Combo Person" in the category add box
    And I press the save combo in the category add box
    Then the category add box should be empty
    And I should see "Combo Person" in the category cards

  Scenario: The save combo adds nothing from an empty add box
    Given I am on the app
    And a person "Alice" exists
    When I click the "Categories" button
    And I press the save combo in the category add box
    Then the category add box should be empty
    And I should see "Alice" in the category cards

  Scenario: Escape closes the open card and puts the cursor in the add box
    Given I am on the app
    And a person "Alice" exists
    When I click the "Categories" button
    And I expand the card "Alice"
    Then the card "Alice" should be expanded
    When I move focus out of the category fields
    And I press Escape
    Then the card "Alice" should be collapsed
    And the category add box should have focus

  Scenario: Escape on a Group other than the first focuses that Group's own box
    Given I am on the app
    And a place "Lisbon" exists
    When I click the "Categories" button
    And I click the "Places" category tab
    And I expand the card "Lisbon"
    When I move focus out of the category fields
    And I press Escape
    Then the card "Lisbon" should be collapsed
    And the focused element should be "places-filter-search"
