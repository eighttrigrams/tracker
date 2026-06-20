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

  Scenario: Inline-editing a title preserves the description when the detail fetch never lands
    Given I am on the app
    And a resource "Keepme" with description "Precious notes" exists
    When I reload the page
    And I click the "Resources" tab
    And I block resource detail fetches
    And I expand resource "Keepme"
    And I inline-edit the title of resource "Keepme" to "Renamed"
    And I unblock resource detail fetches
    And I reload the page
    And I click the "Resources" tab
    And I expand resource "Renamed"
    Then I should see "Precious notes" in the expanded resource

  Scenario: The description editor never wipes a body that has not loaded
    Given I am on the app
    And a resource "Keepbody" with description "Body to keep" exists
    When I reload the page
    And I click the "Resources" tab
    And I block resource detail fetches
    And I expand resource "Keepbody"
    And I open the description editor for resource "Keepbody"
    And I save the open description editor
    And I unblock resource detail fetches
    And I reload the page
    And I click the "Resources" tab
    And I expand resource "Keepbody"
    Then I should see "Body to keep" in the expanded resource

  Scenario: Clearing a loaded description empties it
    Given I am on the app
    And a resource "Clearbody" with description "Body to clear" exists
    When I reload the page
    And I click the "Resources" tab
    And I expand resource "Clearbody"
    And I edit the description of resource "Clearbody" to ""
    And I reload the page
    And I click the "Resources" tab
    And I expand resource "Clearbody"
    Then the expanded resource has an empty description
