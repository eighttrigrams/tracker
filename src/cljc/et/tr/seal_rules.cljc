(ns et.tr.seal-rules
  "Everything about tracker's seal that is not the cipher.

  Three implementations have to agree about this app's seal, and only one thing
  genuinely differs between them:

  | | |
  | --- | --- |
  | `src/cljs/et/tr/ui/seal.cljs` | WebCrypto, and therefore **asynchronous** |
  | `src/clj/et/tr/envelope.clj` | the server, which holds **no key at all** |
  | `plurama-cli/tracker_seal.clj` | `javax.crypto`, **synchronous** |

  The cipher cannot be shared: WebCrypto returns promises and `javax.crypto`
  returns values, and the browser must keep WebCrypto because the whole point is
  a **non-extractable** key — a synchronous pure-JS AES-GCM would put the key
  bytes back into a JavaScript array, one `JSON.stringify` from being posted
  somewhere.

  But the cipher is the small half. The prefix, what counts as blank, what a
  value is bound to, which columns are sealed, and where prose hides inside an
  audit payload are all pure, and pure things that three hosts must agree about
  belong in one file rather than three. This is that file. It is `.cljc` and it
  loads under Clojure, ClojureScript and babashka, with no reader conditionals at
  all — there is nothing in here a host could disagree about.

  `deps.edn` already has `src/cljc` on `:paths` and `shadow-cljs.edn` already has
  it on `:source-paths`, so the two in-repo readers need no wiring.
  `plurama-cli/bb.edn` reaches it as a sibling checkout, the same idiom it already
  uses for the test fixture.

  The cipher and the three rules that need it live in
  `plurama-cli/seal_envelope.clj` (shared with cookbook) and in `seal.cljs`.
  `tracker/test/fixtures/seal-vectors.edn` is what stops any of them drifting."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; The envelope, as far as anything keyless needs to know it.

(def envelope-prefix
  "Self-describing and versioned. A reader — a person looking at the database, a
  half-migrated row, another implementation — can tell a sealed value from a plain
  one without being told which columns are sealed. It is also the whole of what
  the server knows: tracker's server holds no key and recognises an envelope by
  this string and nothing else."
  "enc:v1:")

(defn sealed?
  "Whether a value is sealed. Only ever a question about the prefix.

  Matched strictly: `enc:v0:`, `encv1:`, `ENC:V1:` and a leading space are all
  plaintext, and the fixture carries them as `:passthrough` so that three
  implementations cannot quietly disagree about a near-miss."
  [v]
  (boolean (and (string? v) (str/starts-with? v envelope-prefix))))

(defn blank-value?
  "The values rule 1 refuses to seal. `nil`, `\"\"`, and whitespace-only.

  Ciphertext is never NULL and never empty, so without this rule every empty body
  would become indistinguishable from a body somebody wrote. In tracker that
  distinction is load-bearing in one specific place:
  `db/journal_entry.clj prune-empty-entries` deletes stale entries whose
  description is `nil` or `\"\"`, in SQL, server-side, with no key. Seal a blank
  and that query stops matching, and journal entries nobody wrote accumulate
  forever with nothing to say why.

  Non-strings are left alone too; a number in a prose column is not this
  function's problem to solve."
  [v]
  (or (nil? v) (not (string? v)) (str/blank? v)))

;; ---------------------------------------------------------------------------
;; What a ciphertext is bound to.

(def bound-as
  "Which name a table's values are bound under — the AAD's first half.

  **Ten tables share one binding, and that is the schema being told the truth.**
  The server moves bodies between these tables verbatim, without a key, in three
  places, and a fourth thing has the same effect:

  - `db/issue.clj convert-issue-to-task` copies an issue's body into a task's.
  - `db/task.clj convert-message-to-task` and `db/resource.clj
    convert-message-to-resource` copy a message's into a task's or a resource's.
  - A category's Group is mutable and **the row keeps its id**
    (`PUT /api/categories/:id/group`), so a value bound to `person/description`
    would stop opening the moment that category became a project.

  Bind per table and the first conversion seals a value shut in the column it
  lands in, with nothing anywhere to say why. Cookbook learned this from a live
  run that sealed a whole version ladder; there is no reason to learn it twice.

  The two-level shape is kept even though the first level is nearly constant,
  because it is what refuses a value moved into some future *second* sealed
  column — and because `:events` is already that second binding.

  `:events` is a different kind of thing: the raw request body a dropped machine
  write carried. That is a JSON document which *may contain* a description, not a
  description, and binding it as one would let somebody holding the file lift it
  into a real column."
  {:tasks :item
   :issues :item
   :meets :item
   :meeting_series :item
   :recurring_tasks :item
   :journals :item
   :journal_entries :item
   :resources :item
   :categories :item
   :events :event})

(defn aad
  "What a ciphertext is bound to: the *meaning* of the column it belongs in, as
  `binding/column` — `item/description`, `event/body`. See `bound-as` for why this
  is not the table name.

  Throws on an unknown table rather than inventing a binding. A table nobody
  classified is a table whose values would be sealed under a name no reader
  expects, and the only useful moment to hear about that is the first one."
  [table column]
  (str (name (or (bound-as table)
                 (throw (ex-info (str "no seal binding for table " table) {:table table}))))
       "/" (name column)))

(def item-description-aad
  "The binding every sealed body in tracker carries, spelled once.

  All ten tables resolve to it — which is exactly the point of one binding — so a
  caller that has a value but not a table (the audit log's payloads are the case)
  can name it directly rather than picking a table at random and implying a
  distinction that does not exist."
  (aad :tasks :description))

(def event-body-aad
  "What a dropped machine write's captured request body is bound to."
  (aad :events :body))

;; ---------------------------------------------------------------------------
;; The inventory.

(def sealed-columns
  "Table → the columns of it that are sealed. Ten tables, one column each, and the
  column has the same name in the database, in the JSON payload and in the
  ClojureScript app-state — one name everywhere, which is unusual and worth not
  spoiling.

  `journals` and `meeting_series` hold no body at all today. They are here
  anyway: a column left out because it happens to be empty is a column nobody
  walks when it fills. `mottos` is left out for a different reason and not that
  one — see `clear-tables`."
  {:tasks [:description]
   :issues [:description]
   :meets [:description]
   :meeting_series [:description]
   :recurring_tasks [:description]
   :journals [:description]
   :journal_entries [:description]
   :resources [:description]
   :categories [:description]})

(def clear-tables
  "Named so that the omission reads as a decision rather than an oversight.

  **`mottos` is a judgement and not a mechanism.** A motto's description is not
  prose; it is a second name for the same thing — *Carpe Diem* / *Seize the day*,
  *Memento Mori* / *Remember death*. The model this seal implements draws its line
  at *names and curated words clear, prose sealed*, and a motto body is on the
  names side of it. There is no private body hiding behind a public title the way
  there is on a task: a motto is its text.

  Sealing one would also have cost the only search in tracker that reads a body
  and is worth reading it — `db/motto.clj` searches `[:title :description]`, which
  is how *death* finds *Memento Mori*. Nine of the ten sealed entities search
  `[:title :tags]` and read a body never, which is why sealing costs them nothing;
  bending that one query to fit the seal would have been the model deforming the
  app rather than fitting it.

  `messages` is the one that matters. Its bodies are written by three producers
  that hold no key and cannot be given one — the IMAP poller and blog's
  forwarding, both on fly, and tracker's own in-process feed worker — and it is
  one of only two list searches that reads a body. The server also *parses*
  message bodies to recover titles for YouTube and Atom items. Sealing them would
  need all of that plaintext. They stay clear, permanently, and that is a decision
  and not a deferral."
  #{:messages :mottos :users :relations :working_on :category_rules :items
    :youtube_channels :podcast_feeds :atom_feeds :issue_categories
    :task_categories :meet_categories :journal_categories :resource_categories
    :meeting_series_categories :recurring_task_categories
    :journal_entry_categories})

(defn sealed-in
  "Which of `table`'s sealed columns this row actually holds in ciphertext. The
  cheapest way to ask *did the pass reach this row*, and it needs no key."
  [table row]
  (filterv #(sealed? (get row %)) (get sealed-columns table)))

;; ---------------------------------------------------------------------------
;; Where prose hides inside `events.payload`.

(defn- present?
  "Whether `path` actually leads somewhere in `m`.

  This is not a nicety. A walk that used `update-in` would call its function with
  `nil` on a missing key and then **put the key there**, so a create event for a
  row that never had a body would grow a `:description nil` — a payload asserting
  something untrue about the row, written by the very pass that was re-encoding it
  for safekeeping."
  [m path]
  (let [parent (get-in m (butlast path))]
    (and (map? parent) (contains? parent (last path)))))

(defn prose-paths
  "Where the prose is in one already-parsed event payload, as `[[path aad] …]`.

  **This returns locations, not values, and that is what lets three hosts share
  it.** Sealing is asynchronous in the browser and synchronous everywhere else, so
  the traversal cannot be shared — but the knowledge of *what the shapes are* is
  the part that would drift, and it is all here.

  Five shapes, because five functions in `server/events.clj` build them:

  | builder | payload |
  | --- | --- |
  | `record-create!` | `{:row {…}}` |
  | `record-delete!` | `{:snapshot {…}}` |
  | `record-update!`, one field | `{:field \"description\" :old-value … :new-value …}` |
  | `record-update!`, several | `{:changes {\"description\" {:old … :new …} …}}` |
  | `recording-mode`'s drop | `{:method … :uri … :body \"<raw JSON>\" …}` |

  A shape this does not recognise yields nothing and passes through whole:
  `record-link!` and `record-unlink!` carry a category title and no prose, and an
  unrecognised sixth shape is better left alone than guessed at.

  Note what is *not* here: titles and tags. The server builds these payloads, and
  the server has no key, so sealing a payload whole is not reachable — it would
  need the key on the machine the seal exists to distrust. Prose inside the log is
  sealed; titles inside the log are clear, which is the line drawn everywhere else
  in this app."
  [payload]
  (when (map? payload)
    (let [item (fn [path] (when (present? payload path) [[path item-description-aad]]))]
      (vec
       (cond
         (map? (:row payload))      (item [:row :description])
         (map? (:snapshot payload)) (item [:snapshot :description])

         (= "description" (:field payload))
         (concat (item [:old-value]) (item [:new-value]))

         (map? (:changes payload))
         (concat (item [:changes :description :old])
                 (item [:changes :description :new]))

         (string? (:body payload))
         (when (present? payload [:body]) [[[:body] event-body-aad]])

         :else nil)))))

;; ---------------------------------------------------------------------------
;; Where prose hides in a response body.

(defn body-prose-paths
  "Every place in one already-parsed response body that is **actually sealed**, as
  `[[path aad] …]`.

  ## Why this is a tree walk and not a shape dispatch

  Cookbook has to recognise the shape of each response — a listing, a version
  ladder, a proposal — because its four prose columns are bound under two
  different names, so it cannot open a value without first working out what kind
  of thing it is looking at.

  Tracker has **one binding across all ten tables**, for the reasons `bound-as`
  gives. That makes the question *what table is this row from?* unnecessary on the
  way in: a `:description` is a `:description`, wherever it sits. So this walks
  the body instead of classifying it, and the practical consequence is the one
  cookbook's own review worried about — **a new endpoint cannot silently go
  unsealed**, because nothing had to be taught about it.

  ## Why it only collects sealed values

  Two reasons, and the second is the important one.

  It is cheaper: an unsealed database, a keyless user's rows, and every response
  before the cutover all cost one walk and no crypto.

  And it is what keeps `messages` out of this without a special case. Message
  bodies are never sealed — see `clear-tables` — so they never carry the prefix,
  so they are never collected. The read path needs no opinion about which tables
  are sealed at all; the prefix already said. Only the *write* path needs that
  opinion, and there the caller knows which endpoint it is calling.

  A category nested inside a task's response is collected too, and correctly: it
  is a `categories` row, its body is sealed, and the walk finds it without anyone
  having had to remember that tasks carry categories."
  [body]
  (letfn [(walk [node path acc]
            (cond
              (map? node)
              (let [acc (if (sealed? (get node :description))
                          (conj acc [(conj path :description) item-description-aad])
                          acc)
                    ;; An audit payload is the one place with a shape, because it
                    ;; is JSON the server assembled rather than a row. Handled
                    ;; here and then *not* descended into, or the generic
                    ;; :description rule below would collect [:payload :row
                    ;; :description] a second time and the walk would seal or
                    ;; open it twice.
                    payload (get node :payload)
                    acc (if (map? payload)
                          (into acc (for [[p a] (prose-paths payload)
                                          :when (sealed? (get-in payload p))]
                                      [(into (conj path :payload) p) a]))
                          acc)]
                (reduce-kv (fn [a k v]
                             (if (= k :payload) a (walk v (conj path k) a)))
                           acc node))

              (sequential? node)
              (reduce (fn [a [i v]] (walk v (conj path i) a))
                      acc
                      (map-indexed vector node))

              :else acc))]
    (walk body [] [])))

;; ---------------------------------------------------------------------------
;; Knowing which table a value belongs to — for writing, and only for writing.

(def api-segment->table
  "The first path segment of every endpoint that serves or accepts a sealed body.

  Six segments map to `:categories`, because the six Groups — People, Places,
  Workstreams, Projects, Goals, Assets — were merged into one table by
  `073-unify-category-tables` and kept six URLs.

  `messages` and `mottos` are deliberately absent, and their absence is what keeps
  those bodies in the clear on the write path. See `clear-tables`."
  {"tasks" :tasks
   "issues" :issues
   "meets" :meets
   "meeting-series" :meeting_series
   "recurring-tasks" :recurring_tasks
   "journals" :journals
   "journal-entries" :journal_entries
   "resources" :resources
   "people" :categories
   "places" :categories
   "workstreams" :categories
   "projects" :categories
   "goals" :categories
   "assets" :categories})

(def container-key->table
  "The keys an aggregate response nests rows under.

  Note `:journal-entries` **and** `:journal_entries`: `/api/today-board` hyphenates
  and `/api/reports` does not. That is not a thing to tidy up from in here — both
  are live response shapes, and a map that knew only one of them would silently
  stop indexing half the journal entries on the Reports page."
  {:tasks :tasks
   :meets :meets
   :issues :issues
   :resources :resources
   :journals :journals
   :journal-entries :journal_entries
   :journal_entries :journal_entries
   :categories :categories
   :meeting-series :meeting_series
   :meeting_series :meeting_series
   :recurring-tasks :recurring_tasks
   :recurring_tasks :recurring_tasks})

(defn endpoint-table
  "The table an endpoint's own rows belong to, or `nil`.

  `/api/tasks/123?detail=full` → `:tasks`. `/api/today-board` → `nil`, because
  its rows are not its own and the path into the body is what says whose they
  are."
  [endpoint]
  (when (string? endpoint)
    (let [path (first (str/split endpoint #"\?"))
          segs (remove str/blank? (str/split path #"/"))
          segs (if (= "api" (first segs)) (rest segs) segs)]
      (get api-segment->table (first segs)))))

(def convert-endpoint->table
  "The two message conversions, and the table each one lands a body in.

  `POST /api/messages/:id/convert-to-task` and `…/convert-to-resource` are the
  one write in tracker that **deletes its own original**. The message is
  plaintext by design and permanently — three producers that hold no key write
  them — and the row it creates is in a sealed column, and the `DELETE FROM
  messages` is in the same transaction.

  That is what makes them different from the `\"t \"` title-prefix auto-convert,
  which is accepted as leaving plaintext behind on the argument that *the message
  it was copied from is in the clear in the same database, so sealing the copy
  protects nothing while the original sits beside it*. For these two the original
  does not sit beside it. After the convert the only copy of that prose is
  readable, in a column the whole feature exists to make unreadable on fly.

  So the client sends the body it already holds, sealed, and the server writes
  that instead of copying; and a client that seals and sends nothing is refused,
  because the message is gone either way and there is no second chance at it.

  **`messages` is still not in `api-segment->table`, and must not be.** A message
  body is never sealed and a `PUT /api/messages/:id` must stay in the clear. What
  is sealed here is not the message — it is the row the convert creates, which is
  a different table, and this map is the only place that distinction is drawn.

  Keyed by the last segment, and matched only under `messages`:
  `/api/issues/:id/convert-to-task` is a *different* endpoint and is genuinely
  fine, because it copies ciphertext to ciphertext under the single binding."
  {"convert-to-task" :tasks
   "convert-to-resource" :resources})

(defn- api-segments
  "The path segments of an endpoint after `/api`, query string dropped."
  [endpoint]
  (when (string? endpoint)
    (let [path (first (str/split endpoint #"\?"))
          segs (remove str/blank? (str/split path #"/"))]
      (vec (if (= "api" (first segs)) (rest segs) segs)))))

(defn convert-target
  "The table a message conversion writes its body into, or `nil`.

  `/api/messages/7/convert-to-task` → `:tasks`. Everything else, including every
  ordinary message write and `/api/issues/7/convert-to-task`, → `nil`.

  It is deliberately **not** `endpoint-table`, and the difference is not
  cosmetic. `endpoint-table` answers *whose rows does this endpoint serve*, and
  the answer for a message endpoint is `messages`, which is clear. Worse, the id
  in this path is the **message's**, so a client that resolved this to `:tasks`
  and then looked `[:tasks 7 :description]` up in its stored index would find
  some unrelated task's ciphertext and echo it into the new row — a valid
  envelope, opening to the wrong prose, with no error anywhere. That is exactly
  the failure `stored-entries` refuses to risk, and the reason this is a separate
  question with a separate answer: a convert is a **create**, and a create has
  nothing to echo."
  [endpoint]
  (let [segs (api-segments endpoint)]
    (when (and (= "messages" (first segs)) (= 3 (count segs)))
      (get convert-endpoint->table (nth segs 2)))))

(defn stored-entries
  "`[[table id column ciphertext] …]` — what a client should remember about the
  sealed values in one response, so that a later write of an unchanged body can
  echo the very bytes already stored rather than spending a fresh nonce on them.

  ## Keyed by row, never by text

  Two tasks may legitimately say the same sentence, and echoing one's ciphertext
  into the other would leak exactly the equality a fresh nonce per value exists to
  hide. So the key is `[table id column]`, and a value whose row cannot be
  identified with certainty **is not indexed at all**.

  ## Uncertainty degrades to sealing, never to guessing

  That last rule is the whole safety argument. The cost of failing to index a row
  is one fresh envelope on a save that changed nothing — a spurious audit entry,
  which is the very thing the echo rule exists to avoid, so it is a real cost but
  a bounded one. The cost of indexing it *wrongly* would be echoing some other
  row's ciphertext into this one: a valid envelope, opening to the right text,
  quietly asserting that two rows are equal. There is no error message for that
  and no way to find it afterwards.

  So: the table comes from the last container key on the path
  (`[:tasks 0 :categories 0 :description]` is a category, not a task), or failing
  that from the endpoint, when the body is the row the endpoint names. If neither
  answers, or the row carries no `:id`, the value is skipped."
  [endpoint body]
  (let [root (endpoint-table endpoint)]
    (->> (body-prose-paths body)
         (keep (fn [[path _]]
                 (let [column (last path)
                       row-path (vec (butlast path))
                       row (get-in body row-path)
                       containers (keep container-key->table path)
                       table (or (last containers) root)
                       id (:id row)]
                   (when (and table id (map? row))
                     [table id column (get-in body path)])))) 
         (vec))))

(defn endpoint-id
  "The row id an endpoint names, or `nil`. `/api/tasks/123` → `123`;
  `/api/tasks` → `nil`, which is what a create is and what makes it seal afresh."
  [endpoint]
  (when (string? endpoint)
    (let [path (first (str/split endpoint #"\?"))
          segs (remove str/blank? (str/split path #"/"))
          segs (if (= "api" (first segs)) (rest segs) segs)
          candidate (second segs)]
      (when (and candidate (re-matches #"\d+" candidate))
        #?(:clj (Long/parseLong candidate)
           :cljs (js/parseInt candidate 10))))))

;; ---------------------------------------------------------------------------
;; What a write is aimed at.
;;
;; The browser does not need these — it keeps an index of what it has read, so it
;; already knows what a row holds. A one-shot process does not: `plurama-cli` and
;; the proxy sidecar each see one request and have to ask. These say what to ask.

(defn write-target
  "The table, URL segment and row id a write is aimed at, or `nil` for a path
  that carries no sealed prose — `/api/messages/3`, `/api/auth/login`, anything
  unrecognised.

  The segment is carried rather than derived back from the table because six of
  them map to `:categories` and there is no way back: a write to `/api/people/7`
  must be read back from `/api/people/7`, not from a guess."
  [path]
  (when-let [table (endpoint-table path)]
    (let [clean (first (str/split path #"\?"))
          segs (remove str/blank? (str/split clean #"/"))
          segs (if (= "api" (first segs)) (rest segs) segs)]
      {:table table :segment (first segs) :id (endpoint-id path)})))

(defn prose-in
  "Whether this parsed body actually carries any of the table's sealed columns.

  **The guard worth naming**, and cookbook paid for learning it: without this, a
  write that touches no prose still pays for the echo rule it has no use for.
  `-d '{\"tags\":\"x\"}'` against a task would fetch the whole task to look up a
  ciphertext for a column it is not sending."
  [table parsed]
  (boolean (and (map? parsed)
                (some #(contains? parsed %) (get sealed-columns table)))))

(defn state-path
  "The read that answers what the row holds right now, or `nil` when there is
  nothing to read.

  `nil` for a create, which has no row yet and therefore nothing to echo — the
  right answer, and a fresh seal.

  It is the plain single-row GET and not `?detail=full`, because the plain one has
  no side effects. Cookbook had to be careful here for a sharper reason — a full
  read there counts as a consumption and reorders the shelf — and tracker has no
  such counter today, but the bookkeeping of the seal should not be visible in an
  app's own statistics, whichever app grows one first."
  [{:keys [segment id]}]
  (when (and segment id)
    (str "/api/" segment "/" id)))
