do the filtering for categories in the backend now.

that is, when choosing, unchoosing a category in any of the categories in the category
picker groups in the lhs on the Tasks page, it should have a list of those category names (not ids)
to be passed along to the api call via query param mechanism

understand that inside a single category group, when we select more than one category, those are ORed,
but across category groups, those are ANDed

just take the logic from the frontend and transport it into the db layer in the backend.

write tests there and make sure also to have some tests which test
that his works in conjunction with other features like urgency filtering etc.