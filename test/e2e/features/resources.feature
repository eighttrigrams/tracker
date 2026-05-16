Feature: Resource management

  Scenario: User adds a resource and sees it in the list
    Given I am on the app
    When I click the "Resources" tab
    And I type "https://example.com" in the resources search field
    And I click the resources add button
    Then I should see "https://example.com" in the resources list

  Scenario: YouTube resource shows video embed when expanded
    Given I am on the app
    And a YouTube resource exists
    When I reload the page
    And I click the "Resources" tab
    And I expand the first resource
    Then I should see a YouTube embed
