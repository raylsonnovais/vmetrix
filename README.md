# VMetrix Query Manager — Proof of Concept

A metadata-driven SQL query builder. It receives an abstract, domain-oriented query specification
(logical `entity` + `field`, a recursive filter tree, sorting, a row cap) and returns the
corresponding **parameterised SQL** with the JOINs resolved automatically from relationship metadata.

The central idea is that **the data model lives in configuration, not in Java**. Entities, fields,
data types, relationships and the comparator-per-type matrix are all rows in `META_*` tables, loaded
into an immutable in-memory catalog at startup. Adding a new entity or field is a metadata change plus
a reload — no code change (there is a test that proves exactly this). The engine never trusts the
input for identifiers: logical names are looked up in the catalog, and only the physical names it
returns are ever written into SQL, while every filter value becomes a named bind parameter.

## How to run

Requires JDK 17+ only — the bundled Maven Wrapper fetches Maven itself, so no local Maven install is
needed. One command starts everything (embedded H2 in Oracle-compatibility mode, schema + seed
applied, catalog loaded):

```bash
./mvnw spring-boot:run       # starts on http://localhost:8080
./mvnw test                  # 81 tests (unit + integration)
```

A local `mvn` works just as well if you have it (`mvn spring-boot:run` / `mvn test`).

### Endpoints

| Method | Path | What it does |
|--------|------|--------------|
| `POST` | `/api/query/build` | Validate, then generate SQL — or 400 with the structured errors |
| `POST` | `/api/query/validate` | Validate only → `{"valid": true}` or 400 with all errors |
| `POST` | `/api/query/execute` | Build the SQL, run it against the seed, return the rows (bonus) |
| `GET`  | `/api/metadata/entities` | The catalog projected: entities, fields, relations |
| `GET`  | `/api/metadata/comparators` | Valid comparators per data type |
| `POST` | `/api/metadata/reload` | Rebuild the catalog from the `META_*` tables |

### `POST /api/query/build` — the challenge's Section 5.1 example

```bash
curl -s -X POST http://localhost:8080/api/query/build -H 'Content-Type: application/json' -d '{
  "select": [
    { "entity": "transaction", "field": "txnDate" },
    { "entity": "transaction", "field": "amount" },
    { "entity": "transaction", "field": "currency" },
    { "entity": "instrument", "field": "ticker" },
    { "entity": "instrument", "field": "instrumentName" },
    { "entity": "counterparty", "field": "partyName", "alias": "counterpartyName" }
  ],
  "filters": { "operator": "AND", "conditions": [
    { "entity": "transaction", "field": "status", "comparator": "equals", "value": "SETTLED" },
    { "entity": "transaction", "field": "amount", "comparator": "greaterThan", "value": 1000000 },
    { "operator": "OR", "conditions": [
      { "entity": "instrument", "field": "assetClass", "comparator": "in", "value": ["FIXED_INCOME", "EQUITY"] },
      { "entity": "counterparty", "field": "country", "comparator": "equals", "value": "CL" }
    ] }
  ] },
  "sorting": [ { "entity": "transaction", "field": "txnDate", "direction": "desc" } ],
  "maxResults": 500
}'
```

Response (`200 OK`, actual output):

```json
{
  "sql": "SELECT t.TXN_DATE, t.AMOUNT, t.CURRENCY, i.TICKER, i.INSTRUMENT_NAME, p.PARTY_NAME AS \"counterpartyName\" FROM TRANSACTION t LEFT JOIN INSTRUMENT i ON t.INSTRUMENT_ID = i.INSTRUMENT_ID LEFT JOIN PARTY p ON t.COUNTERPARTY_ID = p.PARTY_ID WHERE t.STATUS = :p1 AND t.AMOUNT > :p2 AND (i.ASSET_CLASS IN (:p3) OR p.COUNTRY = :p4) ORDER BY t.TXN_DATE DESC FETCH FIRST 500 ROWS ONLY",
  "parameters": { "p1": "SETTLED", "p2": 1000000, "p3": ["FIXED_INCOME", "EQUITY"], "p4": "CL" },
  "resolvedTables": ["TRANSACTION", "INSTRUMENT", "PARTY"],
  "resolvedJoins": [
    "LEFT JOIN INSTRUMENT i ON t.INSTRUMENT_ID = i.INSTRUMENT_ID",
    "LEFT JOIN PARTY p ON t.COUNTERPARTY_ID = p.PARTY_ID"
  ],
  "metadata": { "columnCount": 6, "filterCount": 4, "generatedAt": "2026-07-27T13:43:20Z" }
}
```

Note the nested `OR` group is correctly parenthesised, `INSTRUMENT` was joined even though `counterparty`
appears after it, and every value is a bind parameter — there are no literals in the SQL.

### `POST /api/query/validate` — structured errors (Section 5.4 format)

```bash
curl -s -X POST http://localhost:8080/api/query/validate -H 'Content-Type: application/json' -d '{
  "select": [ { "entity": "transaction", "field": "status" } ],
  "filters": { "operator": "AND", "conditions": [
    { "entity": "transaction", "field": "status", "comparator": "greaterThan", "value": "X" },
    { "entity": "unknownEntity", "field": "foo", "comparator": "equals", "value": "Y" }
  ] }
}'
```

Response (`400 Bad Request`, actual output — every error is collected, not just the first, and the
locating fields appear only when they apply):

```json
{
  "valid": false,
  "errors": [
    { "entity": "transaction", "field": "status", "comparator": "greaterThan",
      "message": "Comparator greaterThan is not valid for string fields" },
    { "entity": "unknownEntity",
      "message": "Entity unknownEntity does not exist in the model" }
  ]
}
```

### `POST /api/query/execute` — run the SQL against the seed (bonus)

`ExecuteResponse` *composes* the `BuildResponse` (so the generated SQL and parameters stay visible)
and adds `rowCount` and `rows`. Each row is keyed by the field's output alias, or its logical
camelCase name — never the raw physical column.

```bash
curl -s -X POST http://localhost:8080/api/query/execute -H 'Content-Type: application/json' -d '{
  "select": [
    { "entity": "transaction", "field": "txnId" },
    { "entity": "transaction", "field": "amount" },
    { "entity": "counterparty", "field": "partyName", "alias": "counterparty" }
  ],
  "filters": { "operator": "AND", "conditions": [
    { "entity": "transaction", "field": "status", "comparator": "equals", "value": "SETTLED" },
    { "entity": "transaction", "field": "amount", "comparator": "greaterThan", "value": 5000000 }
  ] },
  "sorting": [ { "entity": "transaction", "field": "txnId", "direction": "asc" } ]
}'
```

Response (`200 OK`, actual output — the two `SETTLED` transactions over 5,000,000 in the seed):

```json
{
  "query": {
    "sql": "SELECT t.TXN_ID, t.AMOUNT, p.PARTY_NAME AS \"counterparty\" FROM TRANSACTION t LEFT JOIN PARTY p ON t.COUNTERPARTY_ID = p.PARTY_ID WHERE t.STATUS = :p1 AND t.AMOUNT > :p2 ORDER BY t.TXN_ID ASC FETCH FIRST 100 ROWS ONLY",
    "parameters": { "p1": "SETTLED", "p2": 5000000 },
    "resolvedTables": ["TRANSACTION", "PARTY"],
    "resolvedJoins": ["LEFT JOIN PARTY p ON t.COUNTERPARTY_ID = p.PARTY_ID"],
    "metadata": { "columnCount": 3, "filterCount": 2, "generatedAt": "2026-07-27T20:55:36Z" }
  },
  "rowCount": 2,
  "rows": [
    { "txnId": 3,  "amount": 11750000, "counterparty": "BTG Pactual" },
    { "txnId": 13, "amount": 10000000, "counterparty": "Falabella S.A." }
  ]
}
```

## Architecture

```
HTTP JSON
   │  custom FilterNode deserializer  → clean domain QueryRequest (no Jackson in the domain)
   ▼
QueryValidator ──(all errors)──▶ 400 ValidationResponse
   │  ValidatedQuery  (a distinct type: names resolved, comparators checked, values typed)
   ▼
JoinResolver  ──▶ JoinPlan  (root + ordered joins + entityRef→alias + tables, all physical)
   │
   ▼
SqlGenerator  ──▶ GeneratedSql  (SQL text + named bind parameters)
   │
   ▼
200 BuildResponse
```

```
com.vmetrix.querymanager
├── domain
│   ├── metadata   EntityMeta, FieldMeta, RelationMeta, DataType, JoinType, MetadataCatalog (+ immutable impl)
│   └── query      QueryRequest, SelectField, Sorting, FilterNode (sealed: FilterGroup | FilterCondition), Comparator
├── application
│   ├── validation QueryValidator → ValidatedQuery or accumulated ValidationErrors; ValueConverter
│   ├── join       JoinResolver → JoinPlan
│   └── sql        SqlGenerator → GeneratedSql
└── infrastructure
    ├── metadata   JdbcMetadataLoader (reads META_*), CachingMetadataCatalogProvider (cache + reload)
    └── rest       controllers, response DTOs, error advice, jackson/ (the FilterNode deserializer)
```

Two boundaries carry most of the design weight:

- **`JoinPlan` is 100% physical.** The resolver produces physical table names, aliases and ON-columns;
  the SQL generator only concatenates strings and never touches the catalog. This is what keeps the
  generator trivial and what makes `p.PARTY_NAME` vs `p2.PARTY_NAME` (PARTY joined twice) fall out for free.
- **`ValidatedQuery` is a distinct type from `QueryRequest`.** `SqlGenerator.generate` accepts only a
  `ValidatedQuery`, so it is *impossible, by the type system,* to generate SQL from unvalidated input.

## Design decisions

**Metadata in database tables (not YAML/JSON).** Section 6.1 requires the DDL to create the metadata
tables, so the database is the natural home. Loading them once into an immutable catalog also delivers
the "metadata caching" bonus, and a `/reload` endpoint rebuilds it — the alternative (files) would have
met neither requirement as directly.

**Relation-as-edge, not table-as-edge.** PARTY is reachable by two relations (`counterparty`, `issuer`).
If the graph edge were the *table*, that would be ambiguous and PARTY could never appear twice with
distinct aliases. Modelling the *relation* as the edge is what makes `counterparty`/`issuer` behave as
first-class query entities that each resolve to their own alias.

**Root chosen by graph specificity, not by name.** The rule is "the most specific base entity (the one
reaching the fewest others) that still reaches everything referenced." The tempting alternative —
"`transaction` if present, else `instrument`, …" — would hardcode entity names in Java and break the
metadata-driven principle. The graph rule is also more intuitive: a query over only `instrument` gets
`instrument` as root (8 seed rows), not `transaction` with a LEFT JOIN (15 rows). It even infers
`transaction` as the root from `counterparty` alone, because that relation hangs off it.

**No ORM — JDBC + `NamedParameterJdbcTemplate`.** The product *is* the generated SQL; an ORM would hide
exactly what is being evaluated. JDBC also keeps the parameter binding explicit and visible.

**Bind onto the domain; keep the domain annotation-free.** The controller takes the domain
`QueryRequest` directly. The only non-trivial part — the polymorphic filter tree — is handled by a
custom `JsonDeserializer<FilterNode>` registered as a `Module` bean in `infrastructure/rest/jackson`, so
serialization knowledge stays out of the domain. Crucially, `comparator` is kept as a raw `String`: an
unknown comparator must become a *structured, accumulated* validation error (Section 5.4), not die
inside Jackson.

**One error shape everywhere.** Validation errors, an ambiguous-join error, a malformed body and the
catch-all 500 all render as the same `{"valid": false, "errors": [...]}` body, so a client needs one parser.

**Ambiguity resolved in the resolver, not the validator.** Whether `party` is ambiguous depends on the
*combination* referenced, not on the entity alone — `party` by itself is a legitimate query. So the
resolver (which sees the combination) refuses with a clear 400; the validator does not pre-emptively ban it.

## Where I chose *not* to abstract

The rubric explicitly rewards choosing the right level of abstraction, so these are deliberate:

- **Comparator Strategy as a `switch`, not 12 classes.** Each comparator renders its own SQL fragment
  and allocates its own binds inside one `switch` in the SQL layer. A class per comparator would be the
  "hierarchy of 8 abstract classes for 3 entities" the brief warns against. The domain `Comparator` enum
  stays free of any SQL.
- **No duplicate wire DTO for the request.** Binding straight onto `QueryRequest` with one custom
  deserializer is less code than mirroring the whole request tree in a parallel DTO hierarchy.
- **An abstraction designed and then removed.** An early `JoinPlan` draft had an extra `EntityAliasing`
  interface that only duplicated the `aliasByEntity` map. I deleted it — abstraction with no second caller.

## Patterns used

- **Composite** — the filter tree (`FilterNode` sealed as `FilterGroup | FilterCondition`) models the
  spec's arbitrarily nested AND/OR groups and is walked recursively for validation and for SQL.
- **Strategy** — each comparator knows how to render its SQL fragment and allocate its bind parameters.
- **Builder** — the SQL generator assembles `SELECT / FROM / JOIN / WHERE / ORDER BY / FETCH` clause by clause.
- **Immutable catalog** — the metadata is loaded once into a shared, read-only snapshot, swapped atomically on reload.

## Security

- **Identifiers only ever come from the catalog.** Input logical names are used purely to look up the
  physical `table`/`column`/`alias`; those are what reach the SQL. `camelCase → SNAKE_CASE` is a metadata
  lookup, never a string transformation (which would let a caller inject an identifier).
- **Every value is a named bind parameter** — including inside `IN (:p)` (the whole list bound to one
  parameter) and `BETWEEN :p1 AND :p2`. There are no value literals in the generated SQL.
- **The one input-derived identifier** — a select field's output alias — is emitted as a double-quoted
  identifier with embedded quotes doubled, so it cannot break out (`bad" FROM x --` → `AS "bad"" FROM x --"`).
- **Comparators are checked against the per-type matrix**, and `maxResults` has a hard ceiling (1000).

Proven by tests: injection attempts in values (`' OR 1=1 --`, `; DROP TABLE`) become bind parameters
and never appear in the SQL; a generic assertion checks the generated SQL contains no single quote at
all; and `outputAliasWithQuotesIsEscapedAndInert` checks the alias escaping.

## Tests

81 tests, unit plus integration:

- **Unit** cover each module against a hand-built catalog: catalog lookups and the comparator matrix;
  the validator's error accumulation, comparator×type checks and value conversion; the join resolver's
  direct/transitive joins, PARTY-joined-twice aliasing, deduplication and deterministic root selection;
  and every comparator, nested-group parenthesisation and injection case for the SQL generator.
- **Integration** (`@SpringBootTest` + MockMvc, real H2 + seed + catalog): the full REST contract.

Two are worth calling out. `generatesTheSpecExampleInFull` asserts the *entire* SQL string for the
Section 5.1 example — the single best proof that the engine does what the document describes. And
`aNewEntityAddedOnlyToMetadataBecomesQueryableAfterReload` inserts a brand-new entity into the `META_*`
tables, reloads, and builds a query against it — proving the metadata-driven claim end to end, with
zero Java change.

## AI usage in development

I built this with **Claude Code** (Anthropic) as a pair-programming agent, working in small phases with
a human review at every checkpoint. A per-phase notebook is in `docs/ai-log.md`; this is the distilled
account.

**Where the AI helped.** Design discussion and the module contracts up front; generating the schema,
the seed and most of the code and tests; and debugging. One concretely useful bit of tooling: with no
`pdftotext` available, it wrote a small ASCII85+Flate decoder in Python to extract the challenge text
from the PDF, then reconstructed the sample dataset and cross-checked the row counts by running the DDL.

**What I corrected, discarded or redid, and why.** This is the honest part, and it is verifiable in the
git history:

- *A cross-method inconsistency the tests never caught* (commit `60dfda3`). `satisfiable` filtered a
  base entity's incoming relations by reachability from the root, but `uniqueRelationTargeting` counted
  *all* of them. Both encode "is there a unique path to this entity from here?" and disagreed:
  `[instrument, party]` passed root selection yet threw "reachable by more than one relation" during
  placement — a factually wrong error, since from `instrument` only `issuer` reaches `party`. The green
  suite missed it because no test exercised an entity reachable by one path *locally* but many
  *globally*. I found it reading the two functions side by side; the fix extracts a single
  reachability-aware helper they both use, plus boundary tests.
- *Framework behaviour that turned protocol errors into 500s* (commit `a7a96a6`). The first error advice
  used a bare `@ExceptionHandler(Exception.class)` without extending `ResponseEntityExceptionHandler`,
  so its resolver ran ahead of Spring's and the catch-all swallowed 405/415 into 500. Fixed by extending
  the base handler and re-bodying protocol responses into the common shape; anchored with 405/415 tests.
- *An environmental assumption the tests exposed.* Adding a second `@SpringBootTest` failed context load
  with "Table PARTY already exists": the named in-memory H2 (`DB_CLOSE_DELAY=-1`) survives across
  contexts in one JVM, so `schema.sql` ran twice. I made the DDL idempotent (`DROP TABLE IF EXISTS`
  first). This one is the mirror of the first: the suite caught what a review would not have.
- *A contract change I escalated rather than made silently.* `FilterCondition.comparator` started as a
  `Comparator` enum, which made "unknown comparator" unrepresentable — so the required structured error
  could not exist. I stopped, laid out three options, and let the human pick the minimal one (comparator
  becomes a raw `String`, resolved in the validator).
- Smaller reversions the same way: the `EntityAliasing` abstraction removed as premature; a
  `Map.copyOf` that dropped parameter order, caught immediately by my own `containsExactly` test.

**What I learned about using AI effectively.** Contracts and interfaces before implementation, then
small phases each ending in human validation, kept the work legible and catchable — a wrong turn was
one phase deep, not five. Escalating genuine rule ambiguity (query root, `like` semantics, `maxResults`
policy) instead of guessing was consistently the right call; the spec even invites it. The sharpest
lesson is that the two classes of defect are caught by *different* means: human review found what the
green suite could not (a subtly inconsistent pair of functions, a framework ordering quirk), and the
suite found what a review would not have (an environmental assumption). Neither alone would have been
enough, and AI-generated code that looks locally correct is exactly the kind that hides the first sort.
