# Evaluator Feedback — Sprint 2: The Activities Listener Creates The Cloned Tasks

## 1. VERDICT

**CONDITIONAL PASS**

Every hard gate passed. All 12 acceptance criteria are implemented and proved, and both halves of the
after-commit contract are bracketed by tests that fail if either annotation is removed. Two cleanups
below, one a small test-quality wart introduced here and one a cohesion debt the contract itself
directed. Neither is behavioural.

The feature is complete. `POST /api/projects/{id}/templates` now clones the standard planogram set into
a programme end to end.

## 2. SCORE

`A 40/40 · B 35/35 · C 24/25 = 99`

* **A — Contract fulfilment 40/40.** 12 of 12 criteria met. The two that matter most, AC 5 and AC 10,
  bracket the after-commit wiring from both sides rather than asserting it once.
* **B — Architecture 35/35.** All 12 ArchUnit rules pass. `activities` imports nothing from
  `programmes` — the sole occurrence of the word is a javadoc line explaining why. Both listener
  annotations present. Nothing in `routes`. `EventBusConfiguration` untouched. Full marks, up from
  34/35 at Sprint 1: the `app-context` drift docked there has been closed (see §5).
* **C — Test quality 24/25.** Strong — three independent angles on AC 11, a vacuity-guarded
  containment test, and unit tests that assert rows rather than mock interactions. One mark off for
  finding 1.

## 3. GATE RESULTS

| Dimension | Gate | Result |
| --- | --- | --- |
| — | `./mvnw clean test` exit code | **PASS** — 0 |
| A | JUnit | **PASS** — `Tests run: 306, Failures: 0, Errors: 0, Skipped: 0` (275 at Sprint 1 close, +31) |
| B | `ModuleBoundaryTest` (12 ArchUnit rules) | **PASS** — `Tests run: 12, Failures: 0, Errors: 0` |
| B | Checkstyle (`IllegalThrows`, `NoRawErrorThrows`, `IllegalCatch`) | **PASS** — `You have 0 Checkstyle violations.` |
| B | SpotBugs | **PASS** — `BugInstance size is 0` |
| C | `jacoco:check` | **PASS** — bundle 94.0% line / 76.6% branch against 85% / 60%. `TaskTemplateEventListener` 100% line, `TaskService` 98.9% line / 91.7% branch against the per-class 70% / 50% |
| — | `git status --porcelain` vs declared file list | **PASS with a disclosure** — see below |

### Disclosure on working-tree scope

`git status` shows six paths beyond Sprint 2's declared list:
`.harness/skills/app-context/SKILL.md`, `programmes/routes/ProjectRoutes.java`,
`programmes/service/ProjectServiceTest.java`, and the archived
`sprint-1-generator-summary.md`.

These are the four cleanups this Evaluator logged against Sprint 1, applied between the two sprints on
explicit human instruction rather than by the Sprint 2 Generator. They were verified green on their own
build — 275 tests, 0 Checkstyle violations, `BUILD SUCCESS` — before Sprint 2 work began, so a Sprint 2
regression cannot be hiding inside them. Recorded here rather than waved through: `how-to-review` §1
requires undeclared changes to be accounted for, and "a human told me to" is an account, not an
exemption.

`ProjectService.java` also appears modified because it is Sprint 1 work that was never committed; the
whole feature sits uncommitted in the working tree.

### LLM-assessed hard gates

| # | Gate | Result | Evidence |
| --- | --- | --- | --- |
| 1 | A required event was never published | **PASS (correctly none)** | This sprint publishes nothing, and should not. The activities are created in `TODO`, so no status transitioned and there is no `TaskStatusChangedEvent` to raise. Asserted positively — `TaskServiceTest.createFromTemplatePublishesNothing` and `TaskTemplateEventListenerTest.theListenerPublishesNothing` both check the **whole** bus is empty, not just one event type |
| 2 | Event wiring that fails silently | **PASS** | `TaskTemplateEventListener.java:54-55` carries both `@TransactionalEventListener(phase = AFTER_COMMIT)` and `@Transactional(propagation = REQUIRES_NEW)`. `EventBusConfiguration` untouched. Both are asserted, not assumed: `anEventPublishedInATransactionIsDelivered` fails without `REQUIRES_NEW` (the write would join an already-committed transaction and never flush) and `aRolledBackTransactionCreatesNothing` fails without `AFTER_COMMIT` |
| 3 | Business logic in a route | **PASS** | No `routes` file changed this sprint |
| 4 | Criteria proved only by a status code | **PASS** | The integration tests deliberately assert persisted rows rather than the `202`. `thePostClonesTheStandardTemplate` carries the reasoning in a comment: the status returns whether or not the listener ran. Every absence assertion has a positive counterpart — `cloningRaisesNoAlert` is paired with the alert tests in `SlaBreachAlertingIntegrationTest`, and the empty-bus checks with the payload assertions in `templateEventRaisesOneActivityPerItem` |
| 5 | A dropped acceptance criterion | **PASS** | All 12 named tests exist and run. Counts reconcile: `TaskServiceTest` 31 = 17 pre-existing + 14 new, `TaskTemplateEventListenerTest` 7, `PlanogramTemplateDeliveryIntegrationTest` 10 |
| — | Invented domain vocabulary | **PASS** | `TaskStatus.TODO`, `TaskCategory.PLANOGRAM`/`GENERAL` and all four `TaskPriority` values are from `app-context` §4. No new route, no new error code, no new event. The fifth event is the one Sprint 1 introduced |

The claim worth recording, because it was the design's central risk and a rule settled it rather than a
reviewer's eye: **`activities` imports nothing from `programmes`, and `programmes` imports nothing from
`activities`.** ArchUnit rule 2 (`modulesAreFreeOfCycles`) passes with the listener in place. The
resolved-items payload did exactly what it was designed to do — the subscriber needed neither the
template catalogue nor programme membership to create the rows.

## 4. FINDINGS

**1. `src/test/java/com/cognizant/storeops/PlanogramTemplateDeliveryIntegrationTest.java:282` —
hand-rolled JSON parsing in a test.** `objectIdFrom` extracts the created activity's id with
`indexOf("\"id\":\"")` and `substring`. It works today only because `id` happens to serialise first in
`TaskResponse`; it will silently pick up the wrong field if that record is ever reordered or gains
another `id`-suffixed property, and a wrong id would make
`anExistingTitleIsSkipped` assert against an activity it did not mean to.

MockMvc already exposes the value properly. Read it with
`JsonPath.read(body, "$.id")`, or drop the id comparison entirely — AC 3 asks that the pre-existing
activity be **untouched**, which the category and status assertions already establish, and the
`updatedAt` clause of that criterion is covered at service level by
`TaskServiceTest.createFromTemplateSkipsTitlesAlreadyPresent`. Deleting the parser loses no coverage.

**2. `src/main/java/com/cognizant/storeops/activities/service/TaskService.java` — the class is now the
activities module's catch-all.** It holds eight public methods across three unrelated concerns: CRUD
(`list`, `getById`, `create`, `update`), cross-module reads (`findByProjectId`, `findByStoreId`), the
SLA sweep (`publishOverdueBreaches`), and now template cloning (`createFromTemplate` plus five private
helpers).

**Not a fault of this sprint.** `sprint-2-contract.md` named
`service/TaskService.createFromTemplate(...)` explicitly, and following the contract is correct
behaviour. But the module's own `TaskBulkStatusService` is the standing precedent for extracting a
distinct operation into its own service, and template cloning is at least as separable as bulk status
was. Recommend a `TaskTemplateService` in a future refactor sprint, and — more usefully — that the
Planner reach for the extraction at contract-writing time next time, since the Generator cannot make
that call for itself without breaking the contract it was given.

### Accepted, not findings

* **The sixth per-item rule.** `generator-summary.md` §3 declares a rule the contract did not state:
  an item with a null or blank title is skipped. Reviewed and accepted. The contract's own guardrail
  says the listener must not throw, and `item.title().trim()` on a null title is an NPE raised
  after commit — swallowed by the `ErrorHandler`, costing the whole batch. The rule is required *by*
  the contract even though it is absent *from* it, which makes this a gap in the contract rather than
  scope creep. Covered by `createFromTemplateSkipsUntitledItems`. **Planner note:** the five stated
  rules covered unparseable enums and unknown assignees but not an unusable title; the same omission
  will recur on the next listener contract unless the pattern is generalised to "every field the
  listener reads needs a stated degradation".
* **`createFromTemplate` is not `@Transactional`.** Correct as written. The listener owns the boundary
  with `REQUIRES_NEW`, and this path publishes nothing, so no after-commit delivery depends on a commit
  here. It matches `create`, which is also non-transactional for the same reason. The hard gate in
  `evaluation-criteria` §3 is about publishers, and this is not one.
* **The Sprint 1 test whose expectation moved.**
  `PlanogramTemplateIntegrationTest.theEndpointItselfWritesNoActivity` flipped from one activity at
  store-002 to five, and was renamed. This is what the Sprint 1 review asked for — updated rather than
  deleted — and the comment records why the number moved, which is what stops a later reader
  "restoring" it.

## 5. CLOSED FROM SPRINT 1

`app-context` drift, open across four consecutive sprints and the highest-priority item in
`sprint-1-run-log.md`, is **closed**. §3 now lists all twelve endpoints, §5 all five events including
`REGIONAL_ROLLUP_REQUESTED` and `PROGRAMME_TEMPLATE_REQUESTED`, §6/§4 carry `PROGRAMME_CLOSED`, and §8's
sanity check asks about a sixth event rather than a fourth. A note was added recording that departments
are a free-text `users.department` string with no enum, which is what this feature had to infer from the
seed rather than read from the vocabulary.

The other three Sprint 1 cleanups are also closed: the duplicate `Endpoint 10` javadoc tag is now
`Endpoint 12`, the summary's test count reconciles at 13, and
`ProjectServiceTest`'s inactive-member assertion now names its four positions instead of
`containsOnlyNulls()`.

That leaves the structural point from `sprint-1-run-log.md` standing and unaddressed: nothing in
`CLAUDE.md` §3 routes a CONDITIONAL PASS cleanup back to a Generator. These four closed because a human
was asked and said yes, not because the harness has a path for it. Finding 2 above will need the same
intervention.
