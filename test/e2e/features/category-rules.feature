Feature: Category rules

  Scenario: A rule auto-assigns its target and auto-selects it as a filter
    Given I am on the app
    When I click the "Categories" button
    And I click the "People" category tab
    And I add a category entry called "Alice"
    And I click the "Projects" category tab
    And I add a category entry called "Alpha"
    And I click the "Rules" category tab
    And I add a rule from "Person: Alice" to "Project: Alpha"
    Then I should see a rule from "Person: Alice" to "Project: Alpha"
    When I click the "Back" button
    And I click the "Tasks" tab
    And I add a task called "Rule task"
    And I assign the person "Alice" to task "Rule task"
    Then task "Rule task" should show the tag "Alpha"
    When I filter by person "Alice"
    Then the "projects" filter should show "Alpha" as selected
