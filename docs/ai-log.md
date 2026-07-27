# AI usage log

A running, honest record of how AI (Claude Code) was used while building this project, and — most
importantly — the moments where its output was corrected, discarded, or redone, and why. This log is
the raw material for the README's "AI usage in development" section, kept concrete by writing entries
as they happen rather than reconstructing them at the end.

## Tooling

- **Claude Code** (Anthropic) as the primary pair-programming agent, driving design discussion,
  code generation, the schema/seed, and tests.

## Phase 0 — Contracts

**Where AI helped**
- Extracted the challenge text from the PDF (no `pdftotext`/poppler available) by writing a small
  ASCII85+Flate stream decoder in Python — faster than manual transcription.
- Reconstructed the sample dataset (10 PARTY / 8 INSTRUMENT / 15 TRANSACTION) and the comparator×type
  matrix (5.3) from the PDF's two-column layout, then cross-checked row counts by running the DDL.
- Drafted the metadata schema, the domain model, and the module interfaces.

**Corrections / judgement calls (kept honest)**
- *Over-engineering caught and reverted:* an early `JoinPlan` draft included an extra
  `EntityAliasing` inner interface that only duplicated the `aliasByEntity` map. Removed it — the
  challenge explicitly penalises abstraction for its own sake ("a hierarchy of 8 abstract classes
  for 3 entities is excessive").
- *Data-reading decision, not a guess to hide:* for the equity (id 3) and fund (id 6) instruments the
  PDF shows a single `0` where maturity/coupon sit. Read as `MATURITY_DATE = NULL`, `COUPON_RATE = 0`.
  Documented in `data.sql` so the choice is auditable.
- *Seed gaps made explicit:* `CREATED_AT` (NOT NULL, absent from the sample) seeded deterministically
  as `TXN_DATE 09:30:00`; `NOMINAL_VALUE` (absent) left NULL. Chosen to give the `timestamp` type real
  values to test, and recorded rather than silently invented.
- *Reserved-word risk verified, not assumed:* rather than trust that `TRANSACTION` is/ isn't reserved
  in H2 Oracle mode, ran the full DDL + seed + a JOIN + `FETCH FIRST` through H2's `RunScript`. It
  applies cleanly unquoted, so no quoting was added.

**Open questions raised to the human, and how they were decided** (preferring a question over an
assumption, as the spec invites — these bind in Phases 2/3):
- **`like` semantics:** pass the caller's pattern verbatim as a bind parameter (`col LIKE :p`); the
  caller owns `%`/`_`, no auto-wrapping and no wildcard escaping.
- **`timestamp` filtering:** interpret values as `LocalDateTime` with no timezone conversion (the H2
  column is timezone-naive); accept ISO local date-time and also a plain date (= start of day).
- **`maxResults`:** default 100 when omitted; hard ceiling 1000; a request above the ceiling is
  **rejected with a validation error** rather than silently clamped (transparency over tolerance).

## Phase 1 — Metadata catalog

**Where AI helped**
- Wrote the JDBC loader, the immutable catalog, and the caching provider, plus unit and integration
  tests, in one pass.

**Corrections / judgement calls (kept honest)**
- *Ordering bug caught before commit:* the first catalog draft copied comparator sets with
  `Set.copyOf(...)`, which drops iteration order. Since `GET /api/metadata/comparators` should be
  reproducible, switched the loader to `EnumSet` (declaration order) and the catalog to an
  order-preserving unmodifiable `LinkedHashSet`. Verified with a "stable order" assertion.
- *Idiomatic startup ordering over a timing hack:* to guarantee the catalog loads only after
  `schema.sql`/`data.sql` have run, used Spring Boot's `@DependsOnDatabaseInitialization` rather than
  an `ApplicationRunner` that could race the datasource init or leave a window where the server is up
  but the catalog is empty.
- *Fail-loud on bad metadata:* the loader resolves every `data_type`/`join_type`/`comparator` token
  against its enum at load time and throws with a precise message on an unknown token, instead of
  silently building a partial catalog.

## Phase 2 — Query validator (Part A)

**Contract change escalated to the human (not decided unilaterally)**
- The Phase 0 `FilterCondition.comparator` was already a `Comparator` enum, which made an *unknown
  comparator* unrepresentable in the domain — so the required "unknown comparator → structured error"
  rule and its test could not exist. Flagged this, offered three options, and the human chose the
  minimal one: change `FilterCondition.comparator` to a raw `String` (wire name) so the validator
  resolves it via `Comparator.fromWire` and reports unknown/invalid ones as accumulated errors. This
  also removed an inconsistency (entity/field were already raw strings; comparator was the odd one).

**Corrections / judgement calls**
- *Avoided a Java 17 preview feature:* used `instanceof` pattern matching to walk the sealed
  `FilterNode` instead of `switch` pattern matching, which is still preview in Java 17 and would have
  forced `--enable-preview`. The `switch` expression is used only over the (non-preview) enum
  cardinality.
- *Value conversion isolated:* `ValueConverter` is a small standalone unit that throws
  `ValueConversionException` on any failure, so a malformed value becomes a structured error and never
  reaches the database. `BigDecimal` is built from the number's string form to avoid binary-float
  surprises.

## Phase 2 — Join resolver (Part B)

**Judgement calls (kept honest)**
- *Root derived from the graph, not hardcoded names:* instead of coding "transaction, else instrument",
  the resolver picks the most specific base entity (the one reaching the fewest entities) that still
  reaches every referenced name. This keeps the rule metadata-driven and gives the right answers for
  every case — including inferring `transaction` as root from `counterparty` alone, and choosing
  `instrument` (not `transaction`) for `[instrument, issuer]`.
- *Ambiguity refused, not invented:* the one genuinely ambiguous case — `party` referenced directly
  while another entity forces a different root, so PARTY could be reached via either `counterparty` or
  `issuer` — throws `JoinResolutionException` rather than guessing. Flagged to the human in the phase
  summary; it does not occur in the spec's vocabulary (PARTY is always reached via a relation alias).

**Corrections**
- *Test cruft removed before commit:* an early draft of the resolver test carried an unused
  `emptyMatrix()` helper guarded by `@SuppressWarnings("unused")` just to keep some imports — deleted
  it and the imports, passing the empty comparator matrix inline (the resolver ignores comparators).

**Bug found in human code review, not by the test suite (honest example for the README)**
- `satisfiable` filtered incoming relations by reachability from the root, but `uniqueRelationTargeting`
  counted *all* relations pointing at an entity. Both encode the same idea — "is there a unique path
  to this entity from here?" — and disagreed: `[instrument, party]` passed root selection yet threw
  "'party' reachable by more than one relation (ambiguous)" during placement, even though from
  `instrument` only `issuer` reaches `party`. The green suite never caught it because no test exercised
  a base entity reachable by one path locally but many paths globally. Fix: extracted one
  reachability-aware `relationsTargeting(entity, reachableSources)` helper used by both call sites, so
  they cannot diverge again; added tests anchoring the boundary between "unique from here"
  (`[instrument, party]` → via `issuer`) and "genuinely ambiguous" (`[transaction, party]` → refused
  at root selection). Lesson: AI-generated code that looks locally correct can hide a cross-method
  inconsistency two green suites apart — a human reading the two functions side by side caught it.

## Phase 3 — SQL generator

**Where AI helped**
- Wrote the generator (Builder-style assembly, recursive Composite WHERE walk, per-comparator Strategy
  as a `switch`, sequential bind allocation) and its 21 tests in one pass, including the full spec 5.1
  example asserted end to end.

**Judgement calls (kept honest)**
- *Domain kept SQL-free:* the per-comparator Strategy lives as a `switch` in the SQL layer rather than
  as an abstract method on the `Comparator` enum, honouring the Phase 0 decision that the domain enum
  carries no dialect knowledge. No 12-class hierarchy.
- *Parenthesisation:* a nested group with more than one child is wrapped in parentheses; the root group
  and single-child groups are not. This guarantees `a AND (b OR c)` never collapses, without emitting
  noisy outer parentheses.
- *`in`/`notIn` as one list parameter:* `col IN (:pN)` with the whole `List` bound to `pN`; the
  `NamedParameterJdbcTemplate` expands it. No manual `(:p1, :p2, ...)` construction, still zero
  interpolation. `maxResults` is inlined into `FETCH FIRST n ROWS ONLY` (a validated int, no injection
  surface). The one input-derived identifier — a select field's output alias — is emitted as a
  double-quoted identifier with embedded quotes doubled, as defence in depth.

**Correction caught by a test I wrote**
- The first `GeneratedSql` used `Map.copyOf(parameters)`, which does not preserve insertion order; the
  `containsExactly` assertion on the spec example failed ("p4 before p1"). Since the response should be
  deterministic (`p1, p2, ...`), changed `GeneratedSql` to an order-preserving unmodifiable
  `LinkedHashMap`. A case where the test earned its keep immediately.
