What I am about to describe relates to the Tasks Tab, to the Resources Tab, and to the Meets tab. All of those host their own resource types. - On the tasks page, we see the individual tasks.
- On the resources page, we see the individual resources.
- On the meets page, we see the individual meetings.
Now I want to be able to relate all of them that should be done in a single database table. And the table structure should be something like from to, so each relation has a from and a to, or source and a target. I don't care too much how it is called. And then on both sides, we need a resource ID or an item ID, as well as the table, because the IDs are counting up, but each table has its own sequence. So the combined key for source and for targets must be something like resource type and item type and then id. And we have that all ready in the URLs. Let's call. If the from item is a message, let's give it the shorthand msg. For the table And then the id That is the combine key. And the same for the target, of course. So this is our join table. And I want this to be in a joint table because it's an M to N relationship. And maybe later, but not today, I want to add additional metadata to the relationships. 
Next thing is very important relations. Let's have them bi-directional.

Now, for the mechanism of linking at the very top in the nav bar, right to the left of the dark mode/light mode switcher, there should be an item of the same size. The symbol should be, I guess, like a circle with another circle in it. Something reminiscent of a target. This menu item should show up whenever I'm on the tasks page, on the resources page, or on the meets page. Now, when I click it, it turns blue. I can visit the tasks resources or meets tab and then search filter, but then, while that thing is on, the first item I click on becomes the source. And the second item can be on a different tab. For example, the first one could have been on the tasks tab and the second one on the meets tab. Whenever I click the second item, then the link happens and the menu item becomes highlighted again. So that means I click on this new symbol at the top, and then relation making mode becomes active. But I can also abort by clicking before I have both selected, both items. When I click this item again, then it becomes unmarked and the relation making process gets aborted until I press it again. 

Now the next thing: where should they be shown?

Relations should be shown right where the badges are shown. When I uncollapse an item at the position where I normally can add badges for the different categories, I won't be able to add relations there, but I will be able to remove relations there. When an item is collapsed, this is just like showing up just like regular badges.

We have badges for four categories, which are normally shown as four groups and different colors. We just give it a fifth color so relations get their own color. 

# Link-Symbols

For ***what*** to click **on the items** to relate them

I think the leanest thing is that, whenever the mode of relations is active, then any item begins not with the title or the hour of the day (in case it has a due date). Now, instead, it begins also with that same symbol, just prepended before the title to the left of the item's title, be it a task, a meeting, or a resource.
This button acts as a link button, so nothing else of the task is relevant; whatever I click doesn't matter here. The only thing that matters is:
1. First of all, I click in the nav bar on the symbol for linking.
2. I navigate and find an item which I want to link.
3. I click on its link symbol.
4. I search the target for linking.
That means that, only when the link mode (the relations mode) is activated, then this new thing gets shown as part of the title. When this mode is off, then it doesn't get shown, and everything looks and behaves just as before.

# Deletion

When deleting an item, make sure to remove also relations to/from that item.
