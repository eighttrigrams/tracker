currently we mostly a single app-state atom, which i consider a bit of a reagent anti-pattern.

each high level view like tabs or pages should get their own state atom.

lets begin with one for the Mail tab.

Also, I want all atoms to start with an asterisk, i.e. *app-state, *my-atom etc.