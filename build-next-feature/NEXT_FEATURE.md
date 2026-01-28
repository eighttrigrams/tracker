When in Tasks view, and no category filter is selected, and I click on a tasks category badge, 
it selects that category as a filter. Only works to select the first category filter. 

When a category filter is selected, this feature should not be available.

We need to ensure this does not interfere negatively with Drag and Drop for rearranging the sort order
in Manual sort mode. I.e. when the pointer is on/above a category badge, probably events propagation 
should be stopped or something.
