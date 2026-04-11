Feature: Ledgers

  Scenario: Adding plain text creates a ledger instead of a resource
    Given I am on the app
    When I click the "Resources" tab
    And I type "My shopping list" in the resources search field
    And I click the resources add button
    Then I should see "Ledger" badge on resource "My shopping list"

  Scenario: Adding a URL still creates a resource
    Given I am on the app
    When I click the "Resources" tab
    And I type "https://example.com" in the resources search field
    And I click the resources add button
    Then I should see "example.com" badge on resource "https://example.com"
