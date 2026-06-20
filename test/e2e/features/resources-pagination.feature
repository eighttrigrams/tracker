Feature: Resource pagination and lazy descriptions

  Scenario: List shows the first page with a See more button
    Given I am on the app
    And 60 resources exist
    When I click the "Resources" tab
    Then I should see 50 resources in the list
    And I should see the resources See more button

  Scenario: See more appends the rest and then disappears
    Given I am on the app
    And 60 resources exist
    When I click the "Resources" tab
    And I click the resources See more button
    Then I should see 60 resources in the list
    And I should not see the resources See more button

  Scenario: Expanding a card lazy-loads its description
    Given I am on the app
    And a resource "Notable" with description "Lazy loaded body text" exists
    When I click the "Resources" tab
    And I expand resource "Notable"
    Then I should see "Lazy loaded body text" in the expanded resource

  Scenario: Editing a description persists after reload
    Given I am on the app
    And a resource "Editable" with description "Original body" exists
    When I click the "Resources" tab
    And I expand resource "Editable"
    And I edit the description of resource "Editable" to "Updated body text"
    And I reload the page
    And I click the "Resources" tab
    And I expand resource "Editable"
    Then I should see "Updated body text" in the expanded resource

  Scenario: Filtering returns the full set without See more
    Given I am on the app
    And 60 resources exist
    When I click the "Resources" tab
    And I type "Resource" in the resources search field
    Then I should see 60 resources in the list
    And I should not see the resources See more button
