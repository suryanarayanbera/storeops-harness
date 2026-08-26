# Sprint 2 Run Log

## Summary

| Field | Value |
| --- | --- |
| Sprint ID | 2 (final — second of two in the planogram task template feature) |
| Goal | `activities` subscribes to `PROGRAMME_TEMPLATE_REQUESTED` and turns each carried item into a `Task`: `TODO`, `PLANOGRAM`, the template's priority, the resolved assignee. Titles already on the programme are skipped, so a repeat call creates nothing |
| Modules touched | `activities` (listener, service) |
| Final verdict | CONDITIONAL PASS |
| Iterations used | 1 of 3 |
| Escalated | no |
| Estimated token cost | ~21.9k (feature total ~46.2k) |

`mvn clean test` exit 0. JUnit 306/306 (275 at Sprint 1 close, +31), ArchUnit 12/12, Checkstyle 0
violations, SpotBugs `BugInstance size is 0`, JaCoCo bundle 94.0% line / 76.6% branch against floors of
85% / 60%; `TaskTemplateEventListener` 100% line, `TaskService` 98.9% line / 91.7% branch against the
per-class floor of 70% / 50%.

**Feature complete: planogram task template, both sprints closed.**

## Iterations

| # | Verdict | Gate or rule that failed | Fix applied |
| --- | --- | --- | --- |
| 1 | CONDITIONAL PASS | none — six automated gates and six LLM-assessed hard gates passed on the first attempt | none required; two cleanups left outstanding, one of them carried debt rather than new |

## Files Changed

### activities — listener
- `listener/TaskTemplateEventListener.java` — **new**, and a new `listener` package for this module.
  One handler carrying `@TransactionalEventListener(phase = AFTER_COMMIT)` **and**
  `@Transactional(propagation = REQUIRES_NEW)`. Delegates to the service, logs created-of-carried.
  Imports nothing from `programmes`.

### activities — service
- `service/TaskService.java` — added
  `createFromTemplate(String projectId, String storeId, List<TemplateTaskDefinition> items)` returning
  the activities actually created, plus private helpers `titleKey`, `priorityOrDefault`,
  `categoryOrDefault`, `trimmed`, `knownAssigneeOrNull`. Every existing method untouched. Deliberately
  not `@Transactional`: the listener owns the boundary and this path publishes nothing.

### tests
- `activities/listener/TaskTemplateEventListenerTest.java` — **new**, 7 tests. Handler constructed and
  invoked directly per `how-to-test` §1, over a real `TaskService` on `FakeTaskRepository`.
- `PlanogramTemplateDeliveryIntegrationTest.java` — **new**, 10 tests. `@SpringBootTest`, SLA sweep
  disabled, `@DirtiesContext(BEFORE_EACH_TEST_METHOD)`.
- `activities/service/TaskServiceTest.java` — 14 new tests; the 17 pre-existing unchanged.
- `PlanogramTemplateIntegrationTest.java` — Sprint 1's `applyingTheTemplateCreatesNoActivityYet`
  renamed to `theEndpointItselfWritesNoActivity`, expectation flipped from one activity at store-002 to
  five. Updated rather than deleted, as the Sprint 1 review required.

No new JPA entity, no schema change, no `data.sql` change, nothing in `routes`,
`EventBusConfiguration` untouched.

### Also in the working tree — the four Sprint 1 cleanups

Applied between sprints on explicit human instruction, not by the Sprint 2 Generator, and verified
green on their own build (275 tests, 0 violations) before Sprint 2 began:

- `.harness/skills/app-context/SKILL.md` — §3 all twelve endpoints, §5 all five events, §4/§6
  `PROGRAMME_CLOSED`, §8 sanity check corrected, plus a note that departments are free-text
  `users.department` with no enum.
- `programmes/routes/ProjectRoutes.java` — `Endpoint 10` → `Endpoint 12`.
- `programmes/service/ProjectServiceTest.java` — inactive-member assertion names its four positions
  instead of `containsOnlyNulls()`.
- `sprint-1-generator-summary.md` — test count reconciled at 13.

## Conditional Pass Cleanups

Two. Full detail in `sprint-2-evaluator-feedback.md` §4.

1. **`PlanogramTemplateDeliveryIntegrationTest.java:282`** — `objectIdFrom` extracts a created id with
   `indexOf`/`substring` instead of JSONPath. Works only because `id` happens to serialise first in
   `TaskResponse`. Use `JsonPath.read`, or drop the id comparison entirely — AC 3's `updatedAt` clause
   is already covered at service level, so deleting the parser loses no coverage. **Introduced by this
   sprint.**
2. **`activities/service/TaskService.java`** — now eight public methods across three unrelated
   concerns: CRUD, cross-module reads, the SLA sweep, and template cloning. **Not this sprint's fault**
   — `sprint-2-contract.md` named `TaskService.createFromTemplate` explicitly. `TaskBulkStatusService`
   is the standing precedent for extraction; recommend a `TaskTemplateService`, and that the Planner
   reach for the split at contract-writing time, since a Generator cannot make that call without
   breaking its contract.

## Quality Trend Notes

* **Ten sprints, ten CONDITIONAL PASSes, no retries, no escalations.** Every sprint across all four
  features has closed on attempt 1 of 3, and none has closed on a clean PASS. The escalation ladder
  still has never been exercised. The consistency is real but it makes CONDITIONAL PASS the harness's
  default verdict rather than its exception, which blunts the signal: a sprint with one cosmetic wart
  and a sprint with four are recorded identically.

  **Worth considering:** either a PASS becomes reachable when the only findings are documentary, or the
  cleanup count starts carrying weight in the routing. As it stands the verdict tells the Monitor less
  than the finding list does.

* **The four-sprint `app-context` drift is closed.** Flagged in
  `regional-rollup-report/sprint-1-run-log.md:149`, again in sprint 2 as "the highest-priority
  documentation debt", again in sprint 3, and again in this feature's Sprint 1. §5 now lists five
  events and §3 twelve endpoints. First time a repeatedly-carried finding has actually been retired.

* **It closed because a human was asked, not because the harness routed it.** This is the structural
  gap Sprint 1's log named and it is still open: `CLAUDE.md` §3 sends both PASS and CONDITIONAL PASS
  straight to the next sprint, so no cleanup ever reaches a Generator. Four reviews correctly
  identified the drift and none could act on it. Sprint 2's finding 2 is now in the same position.
  **Recommend a fifth workflow state — or a rule that a sprint's first act is to clear the previous
  sprint's cleanups — before the next feature starts.**

* **Contract quality is the emerging bottleneck, not code quality.** Two of this feature's findings
  trace to the contract rather than the implementation: the missing blank-title degradation rule
  (`sprint-2-evaluator-feedback.md` §4, accepted) and the `TaskService` cohesion call. Both were
  decisions only the Planner could have made, and in both cases the Generator was correct to follow
  what it was given. As the automated gates keep passing first time, the residual risk is migrating
  upstream into planning.

* **No problem module.** `activities` took the whole of this sprint and produced no boundary finding.
  The two-way import ban between `activities` and `programmes` held in both directions, which was the
  feature's central architectural risk — the resolved-items payload meant the subscriber needed neither
  the template catalogue nor programme membership. The event-wiring gate, historically the one that
  bites, passed with both annotations asserted from either side rather than assumed.

## Token Cost Basis

`(10,989 + 5,874) × 1.3 × 1 = 21,922` ≈ **~21.9k**

* 10,989 words — harness files read or written: `spec.md`, `sprint-2-contract.md`,
  `generator-summary.md`, `evaluator-feedback.md`, the three agent definitions used this sprint, the six
  `SKILL.md` files the Generator and Evaluator are required to read, and `sprint-1-run-log.md` for the
  trend analysis.
* 5,874 words — source and test files written or read: the new listener, `TaskService.java`, its
  repository interface, the three test files touched or created, and `ReportEventListenerTest.java`
  read to establish the listener-test pattern.
* × 1.3 words-to-tokens, × 1 iteration.

**Feature total: ~46.2k** across two sprints (Sprint 1 ~24.3k, Sprint 2 ~21.9k). For comparison, the
three-sprint SLA breach alerting feature ran ~81.5k.
