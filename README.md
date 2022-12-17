# reagent-react18-mount

## Getting started

```bash
$1 clj -M -m server
$2 npx shadow-cljs watch app
```

Visit `localhost:8020`

## REPL Workflow (Server)

Begin with

```clojure
clj:user:> (require '[mount.core :as mount]
                    'server)
nil
clj:user:>
(add-tap (bound-fn* clojure.pprint/pprint))
nil
clj:user:> (mount/start)
{:started ["#'resources/resources" "#'server/http-server"]}
```

Visit `localhost:8020`.

Now edit `resources.clj` (see 'edit' hint; put a 2 there).

Refresh the page.

The REPL output should be:

```clojure
[:resources :down]
[:resources :up]
[:resources 2]
```

### VSCode

- Jack-in
    - deps.edn
        - Profile: :dev

### Package and run

```bash
$ npx shadow-cljs release app
$ clj -M -m uberdeps.uberjar --target server.jar
$ java -cp server.jar clojure.main -m server
visit localhost:3000
```

### Clean

```bash
$ rm -rf resources/public/js/*
```
