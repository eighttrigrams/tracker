Small thing: When we edit a message in the Mail tab,
it should hide the bottom row with the buttons, in exactly the same manner that 
it hides it when we edit task titles/names in the Tasks tab on the task cards.

Because that request here is so small, also use the opportunity to break down the server.clj
into smaller artifacts. Use opportunities to "distribute" dependencies, so one criterion
to break somethin out of the server namespace if it takes a `require` with it entirely
or "leaves one behind" - that is, has a require in only one namespace after the refactoring.