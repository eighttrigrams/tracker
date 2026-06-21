Feature: Meets week-section pagination

  Scenario: Upcoming meets show week sections with only the nearest weeks and a See more button
    Given I am on the app
    And a meet "Soon sync" starting 3 days from now exists
    And a meet "Far sync" starting 40 days from now exists
    When I click the "Meets" tab
    Then I should see "Soon sync" in the meets weeks
    And I should not see "Far sync" in the meets weeks
    And I should see the meets See more button

  Scenario: See more reveals further-future weeks and then disappears
    Given I am on the app
    And a meet "Soon sync" starting 3 days from now exists
    And a meet "Far sync" starting 40 days from now exists
    When I click the "Meets" tab
    And I click the meets See more button
    Then I should see "Soon sync" in the meets weeks
    And I should see "Far sync" in the meets weeks
    And I should not see the meets See more button

  Scenario: Past meets show week sections back in time with a See more button
    Given I am on the app
    And a meet "Recent chat" that started 3 days ago exists
    And a meet "Ancient chat" that started 40 days ago exists
    When I click the "Meets" tab
    And I switch the meets sort to past
    Then I should see "Recent chat" in the meets weeks
    And I should not see "Ancient chat" in the meets weeks
    And I should see the meets See more button

  Scenario: See more on past meets reveals older weeks and then disappears
    Given I am on the app
    And a meet "Recent chat" that started 3 days ago exists
    And a meet "Ancient chat" that started 40 days ago exists
    When I click the "Meets" tab
    And I switch the meets sort to past
    And I click the meets See more button
    Then I should see "Recent chat" in the meets weeks
    And I should see "Ancient chat" in the meets weeks
    And I should not see the meets See more button

  Scenario: Rapid double-clicking See more never skips a week window
    Given I am on the app
    And a meet "Soon sync" starting 3 days from now exists
    And a meet "Mid sync" starting 32 days from now exists
    And a meet "Far sync" starting 60 days from now exists
    And meets responses are delayed
    When I click the "Meets" tab
    And I rapid-double-click the meets See more button
    Then I should see "Soon sync" in the meets weeks
    And I should see "Mid sync" in the meets weeks
    And I should see the meets See more button
    When I click the meets See more button
    Then I should see "Far sync" in the meets weeks
    And I should not see the meets See more button

  Scenario: A failed See more append never skips the next week window
    Given I am on the app
    And a meet "Soon sync" starting 3 days from now exists
    And a meet "Mid sync" starting 32 days from now exists
    And a meet "Far sync" starting 60 days from now exists
    And the first meets See more append fails
    When I click the "Meets" tab
    And I click the meets See more button
    And I click the meets See more button
    Then I should see "Soon sync" in the meets weeks
    And I should see "Mid sync" in the meets weeks
