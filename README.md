# Tracker

An issue tracking system.

## Getting started

```bash
$1 ./dev.sh
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
$ ./deploy.sh
$ ./start.sh
visit localhost:3000
```

### Clean

```bash
$ rm -rf resources/public/js/*
```
