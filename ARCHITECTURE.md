# Architecture

## Per-Entity CRUD Projection

The backend is organized around a per-entity CRUD projection. Each domain entity (task, meet, resource, message, category, user, relation) has its own dedicated namespace at both the **db layer** and the **handler layer**.

### DB Layer (`et.tr.db.*`)

Each entity has its own db namespace handling persistence:

- `et.tr.db.task` - task CRUD, due dates, done/today state, categorization
- `et.tr.db.meet` - meet CRUD, start date/time, categorization
- `et.tr.db.resource` - resource CRUD, categorization, message-to-resource conversion
- `et.tr.db.message` - message CRUD, annotations, merging
- `et.tr.db.category` - people, places, projects, goals CRUD
- `et.tr.db.user` - user CRUD, authentication, language
- `et.tr.db.relation` - cross-entity relations

Shared infrastructure (connection management, query builders, normalizers) lives in `et.tr.db`.

### Handler Layer (`et.tr.server.*`)

Each entity has a corresponding handler namespace:

- `et.tr.server.task-handler` - depends on `db.task`
- `et.tr.server.meet-handler` - depends on `db.meet`
- `et.tr.server.resource-handler` - depends on `db.resource`
- `et.tr.server.message-handler` - depends on `db.message` (+ `db.resource` for message-to-resource conversion)
- `et.tr.server.category-handler` - depends on `db.category`
- `et.tr.server.user-handler` - depends on `db.user`
- `et.tr.server.relation-handler` - depends on `db.relation`

Shared handler infrastructure (auth, request helpers, handler factories) lives in `et.tr.server.common`. Route assembly and server lifecycle live in `et.tr.server`.

The guiding principle is that each handler namespace should ideally only depend on its corresponding db namespace. Cross-entity dependencies (like message-to-resource conversion) are acceptable compromises but should be minimized.

### Tests

DB tests mirror the entity structure and test the db layer directly:

- `tasks-db-test`, `meets-db-test`, `categories-db-test`, `messages-db-test`, `users-db-test`, `relations-db-test`
