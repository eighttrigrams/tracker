# Tracker

An issue tracking system.

## Getting started

```bash
$1 clj -M:dev -m server
$2 npx shadow-cljs watch app
```

Visit `localhost:8020`

## REPL Workflow (Server)

Begin with

```clojure
clj:user:> (start)
{:started ["#'resources/resources" "#'server/http-server"]}
```

Visit `localhost:8020`.

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
