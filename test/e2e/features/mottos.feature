Feature: Mottos

  Scenario: User can navigate to the Mottos tab in settings
    Given I am on the app
    When I open the settings menu
    And I click the "Mottos" tab
    Then I should see the mottos page

  Scenario: User can add and see a motto
    Given I am on the app
    When I open the settings menu
    And I click the "Mottos" tab
    And I type "Carpe Diem" in the motto title field
    And I type "Seize the day" in the motto description field
    And I click the motto add button
    Then I should see "Carpe Diem" in the motto list
    And I should see "Seize the day" in the motto list

  Scenario: User can search mottos
    Given I am on the app
    And a motto "Carpe Diem" with description "Seize the day" exists
    And a motto "Memento Mori" with description "Remember death" exists
    When I open the settings menu
    And I click the "Mottos" tab
    And I type "Carpe" in the motto search field
    Then I should see "Carpe Diem" in the motto list
    And I should not see "Memento Mori" in the motto list

  Scenario: User can delete a motto
    Given I am on the app
    And a motto "Bye" with description "" exists
    When I open the settings menu
    And I click the "Mottos" tab
    And I click delete on motto "Bye"
    And I confirm motto deletion
    Then I should not see "Bye" in the motto list

  Scenario: User can opt in to the motto screensaver
    Given I am on the app
    When I open the settings menu
    And I click the "Mottos" tab
    And I check the screensaver opt-in checkbox
    Then the screensaver opt-in checkbox should be checked

  Scenario: The screensaver shows a motto after the timeout fires
    Given I am on the app
    And a motto "Carpe Diem" with description "Seize the day" exists
    And the motto screensaver is enabled with timeout 5
    When I reload the page
    And I trigger the motto screensaver
    Then I should see the motto overlay containing "Carpe Diem"
    When I click on the motto overlay
    Then the motto overlay should be gone
