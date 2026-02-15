Introduce the concept (new table!) of a **Resource**.
It should get a tab/view, directly after Tasks in the navbar, and be accessible via option+r keybinding.

A resource has the following properties:
- The primary property is a link, which has always to be a URL, no matter what.
- It has a title or name, just like tasks.
- It has the description, just like tasks. The description is marked down, just like tasks.
- It also has a tags field for additional search term terms, just like tasks.

Just like tasks, it should also get an importance picker, but it should not get an urgency picker. 

I don't want resources to show up on the resources page, neither on the tasks page; it is something entirely separate.

However, I want the context scope switcher between private, both, and work, which is shown up only on the tasks and today pages at the moment, to also show up on the resources page and be applied there as well. That is, if on the context switcher I have private selected, then the newly created resource also gets that property correspondingly, just like tasks. The resource should also get that property, and the corresponding picker should also show up when it is an uncollapsed edit view. 

The primary link should, of course, be clickable and lead to a new tab being opened. If the link is a YouTube link, then I want a preview in uncollapsed mode for that task, which just looks like an iframe preview, just like for the messages. 