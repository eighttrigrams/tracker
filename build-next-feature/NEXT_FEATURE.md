The next feature is about the Private|Both|Work switcher.

We want to augment that widget at the top of the page by showing on hover (only on hover)
a checkbox which induces "Strict" mode. 

In normal mode, like right now, when we switch to Private, it *also* shows Tasks
marked as suitable for "Both". It only *does not show* in Private those which are explicitly flagged
as Work (and the other way around).

The strict mode, on the other hand, should show in Private only those explicitly set as Private,
in Both mode only those which are explicitly set at Both, and in Work only which are explicitly set as Work.