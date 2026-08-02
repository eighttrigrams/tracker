Feature: Shift+Option on a badge bypasses the category rules

  Scenario: A plain badge click applies the whole transitive closure
    Given I am on the app
    And test data for rule bypassing exists
    When I click the "Tasks" tab
    And I start counting rule resolutions
    And I click the "Alpha" badge on task "Rule task"
    Then a rule resolution should have been requested
    And the "projects" filter should show "Alpha" as selected
    And the "projects" filter should show "Beta" as selected
    And the "places" filter should show "Lagos" as selected

  Scenario: The owner's example - a pre-selected tail survives, the rule's middle does not
    Given I am on the app
    And test data for rule bypassing exists
    When I click the "Tasks" tab
    And I filter by place "Lagos"
    And I start counting rule resolutions
    And I shift-option-click the "Alpha" badge on task "Rule task"
    Then no rule resolution should have been requested
    And the "projects" filter should show "Alpha" as selected
    And the "places" filter should show "Lagos" as selected
    And the "projects" filter should not show "Beta" as selected

  Scenario: Shift+Option adds only the clicked category
    Given I am on the app
    And test data for rule bypassing exists
    When I click the "Tasks" tab
    And I start counting rule resolutions
    And I shift-option-click the "Alpha" badge on task "Rule task"
    Then no rule resolution should have been requested
    And the "projects" filter should show "Alpha" as selected
    And the "projects" filter should not show "Beta" as selected
    And nothing should be selected in the "places" filter group

  Scenario: Shift+Option keeps an existing selection of the badge's own type
    Given I am on the app
    And test data for rule bypassing exists
    When I click the "Tasks" tab
    And I filter by project "Extra"
    And I start counting rule resolutions
    And I shift-option-click the "Alpha" badge on task "Rule task"
    Then no rule resolution should have been requested
    And the "projects" filter should show "Extra" as selected
    And the "projects" filter should show "Alpha" as selected
    And the "projects" filter should not show "Beta" as selected

  Scenario: A plain click on a badge of an already-filtered type does nothing, Shift+Option still adds it
    Given I am on the app
    And test data for rule bypassing exists
    When I click the "Tasks" tab
    And I click the "Alpha" badge on task "Rule task"
    Then the "projects" filter should show "Beta" as selected
    When I click the "Extra" badge on task "Rule task"
    And I shift-option-click the "Extra" badge on task "Rule task"
    Then the "projects" filter should show "Extra" as selected
    And the "projects" filter should show "Alpha" as selected

  Scenario: Plain Shift+click on a badge still adds a negative filter
    Given I am on the app
    And test data for rule bypassing exists
    When I click the "Tasks" tab
    And I shift-click the "Alpha" badge on task "Rule task"
    Then the sidebar should show "Alpha" as excluded
    And I should not see "Rule task" in the task list

  Scenario: Shift+Option in the sidebar picker is unchanged and still applies the rules
    Given I am on the app
    And test data for rule bypassing exists
    When I click the "Tasks" tab
    And I start counting rule resolutions
    And I shift-option-click "Alpha" in the "projects" filter group
    Then a rule resolution should have been requested
    And the "projects" filter should show "Alpha" as selected
    And the "projects" filter should show "Beta" as selected
    And the "places" filter should show "Lagos" as selected
