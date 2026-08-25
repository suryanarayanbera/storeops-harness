# Sprint 5 Run Log

## Summary

| Field | Value |
| --- | --- |
| Sprint ID | 5 (final — third of three in the SLA breach alerting feature) |
| Goal | Escalate an unresolved breach to the store's `STORE_MANAGER` once a configurable grace period has run from the first `SLA_BREACH` alert, exactly once |
| Modules touched | `alerts` (listener, service); `application.yml` |
| Final verdict | CONDITIONAL PASS |
| Iterations used | 1 of 3 |
| Escalated | no |
| Estimated token cost | ~25.2k (feature total ~81.5k) |

`mvn clean test` exit 0. JUnit 205/205 (181 at Sprint 4 close, +24), ArchUnit 12/12, Checkstyle 0
violations, SpotBugs clean, JaCoCo bundle 92.3% line / 73.3% branch against floors of 85% / 60%.

**Feature complete: SLA breach alerting, all three sprints closed.**

## Iterations

| # | Verdict | Gate or rule that failed | Fix applied |
| --- | --- | --- | --- |
| 1 | CONDITIONAL PASS | none — five automated gates and five LLM-assessed hard gates passed on the first attempt | none required; one cosmetic cleanup left outstanding |

## Files Changed

### alerts — service
- `service/SlaAlertProperties.java` — **new**. `@ConfigurationProperties("storeops.alerts.sla")`
  record with `Duration gracePeriod`; rejects null and negative with `ValidationError` /
  `VALIDATION_FAILED`, accepts zero. Picked up by the existing `@ConfigurationPropertiesScan`, so
  `StoreOpsApplication` needed no change.

### alerts — listener
- `listener/AlertEventListener.java` — `onTaskOverdue()` now dispatches on what has already been
  raised: no prior breach → `raiseBreach`, otherwise → `escalateIfGraceElapsed`. Added
  `SLA_ESCALATION_SUBJECT`, extracted `raiseBreach`, added `escalateIfGraceElapsed` and
  `alreadyEscalated`. `SlaAlertProperties` and `Clock` injected. `onTaskStatusChanged()` untouched;
  both handler annotations unchanged.

### configuration
- `src/main/resources/application.yml` — added `storeops.alerts.sla.grace-period: PT4H`.

### tests — new
- `alerts/listener/SlaEscalationListenerTest.java` — **new**, 12 tests. Split from
  `AlertEventListenerTest` because the fixtures genuinely differ: stage one starts from an empty
  repository, stage two only exists once a breach alert does, and every assertion turns on that
  alert's age against a pinned clock.
- `alerts/service/SlaAlertPropertiesTest.java` — **new**, 8 tests.
- `SlaEscalationIntegrationTest.java` — **new**, 4 tests, `grace-period=PT0S`.

### tests — modified
- `alerts/listener/AlertEventListenerTest.java` — constructor updated via a `listenerOn` helper, with
  a four-hour grace so no stage-one fixture trips into stage two. Still 14 tests, unchanged in
  substance.

### untouched
`TaskOverdueEvent` and all of `shared`, `TaskService`, `SlaSweepScheduler`, `EventBusConfiguration`,
`NotificationEntity`, `NotificationRepository` and both implementations, `UserService`, `data.sql`,
`pom.xml`, every `routes` class. No new event, entity, table, endpoint, error code or enum value, and
no schema change.

## Conditional Pass Cleanups

One item, **outstanding**. This was the final sprint, so no downstream Generator pass will collect it:
it needs a human. (Sprint 4's log identified exactly this pattern — see the trend notes.)

1. **`AlertEventListener:236` — the escalation body renders the grace period as a raw ISO-8601
   token.** A store manager reads "Activity task-001 … is still not DONE **PT4H** after the breach was
   raised". `slaAlertProperties.gracePeriod()` is interpolated through `Duration.toString()`.
   `Notification.body` is display text for a retail manager, not a log line, and `PT4H` is not a thing
   anyone says. AC 1 required the body to name the grace period and it does, literally, which is why
   this scored one point rather than failing a criterion. Fix: format the duration for humans (`4h`,
   or hours and minutes when not whole) in the body only. One line, cosmetic, no behavioural risk, and
   invisible to the existing assertions, which check for `task-001`, `store-001` and `HIGH`.

**Sprint 4's cleanup is closed.** `SlaEscalationListenerTest.aBlockedActivityEscalationDoesNotSuppressTheBreach`
is the listener-level test that stage one discriminates by alert type, sitting on the same fixture as
its stage-two sibling exactly as the finding proposed.

## Quality Trend Notes

Fifth and final entry. Scores across all five sprints: **92, 98, 96, 98, 99**. Iterations: **1, 1, 1,
1, 1**.

* **The 3-attempt escalation budget was never touched.** Five sprints, two features, every one closed
  on the first attempt. That is now a property of the harness rather than a run of luck, and the
  mechanism is identifiable: contracts that name file paths, exact string constants, package
  destinations and the specific boundary each sprint could break leave the Generator very little to
  guess at. The two contract defects found across this feature (a clock instant that contradicted a
  skill, a stale endpoint count) were both *over*-specification — the Planner naming a value it should
  have deferred to the skill or not asserted at all. That is the cheap failure mode to have.
* **Four consecutive sprints with zero behavioural defects in Generator-written production code.**
  Sprint 1 produced one; Sprints 2–5 produced none. Every finding since has been a test gap, a
  contract defect, test-infrastructure fragility, or — this sprint — a cosmetic string. The harness
  stopped catching bad code after its first sprint and spent the rest of its life catching incomplete
  proof and stale documentation. Worth stating plainly in the capstone write-up: that is the harness's
  actual value, and it is not the value one would have predicted from the agent definitions.
* **Run-log predictions finished 4 for 4, all settled by mutation probes.** Sprint 1 → Sprint 2 (the
  unasserted after-commit path). Sprint 3 → Sprint 4 (`@Transactional` deletable with the suite
  green). Sprint 4 → Sprint 5, both parts: the `ESCALATION` subject collision was real and Scenario 6
  caught it; and the context-isolation fixes generalised, with `H2SchemaTest` surviving a new cached
  context untouched. Writing a falsifiable prediction into the run log and settling it in the next
  sprint is the single practice from this harness most worth keeping.
* **Mutation probing escalated in scope across the feature and kept paying.** Sprint 3: none needed.
  Sprint 4: one probe, on an annotation. Sprint 5: two probes, one on a boolean predicate and one on a
  single comparison operator — and the second revealed that the grace boundary is pinned by two tests,
  including the zero-grace case where an off-by-one-instant error stops working entirely rather than
  merely being a second late. Probing a *one-token* change is a reasonable bar for a time-dependent
  rule.
* **The most transferable finding of the whole feature: an end-to-end test is not automatically the
  stronger test.** Probe 1 was caught only by the unit fixture. `task-001` is `TODO` in the seed and
  was never blocked, so the integration test could not construct the colliding `ESCALATION` row at
  all. Integration tests are stronger about wiring and weaker about state they cannot easily arrange.
  Sprint 4 produced the complement: absence assertions cannot detect a dropped transaction, because
  with nothing raised everything is absent. Both belong in `how-to-test`.
* **Cleanups assigned to a following sprint get closed; cleanups landing on a final sprint do not.**
  Now three data points and fully consistent. Sprint 3's cleanup was closed in Sprint 4 (with a better
  fix than proposed). Sprint 4's was closed in Sprint 5. Sprint 2's — the previous feature's final
  sprint — is *still* open, and Sprint 5's will join it. Actionable for the next feature: either plan
  a short buffer sprint to absorb the final sprint's findings, or accept at planning time that the
  last sprint's cleanups become human backlog and say so in the spec.
* **Documentation drift is the harness's one accumulating debt: four items, one closed.** The
  `FailingSubscriber` javadoc (Sprint 2, open), the non-existent `./mvnw` named in six documents
  (Sprints 3–5, open, now the oldest item), the `AlertEventListener` javadoc (Sprint 4, **closed by
  the Generator itself**), and `app-context` §3's "Nine endpoints exist today" when there are ten
  (Sprint 5, open — and it caused a bad assertion in `sprint-5-contract.md`, so drift in
  `app-context` propagates into contracts). `app-context` is the file every agent is instructed to
  treat as authoritative; drift there is the most expensive kind and should be fixed first.
* **Module ownership across the feature was clean and worth recording.** Sprint 3 touched
  `activities` only, Sprint 4 `alerts` + `staff`, Sprint 5 `alerts` only. No sprint touched a module
  it did not own the concern for, no sprint needed a change to `shared`, and the event catalogue
  finished at the same three events it started with. The one new cross-module edge
  (`alerts` → `staff.service`) was added in Sprint 4 and the ArchUnit rules arbitrated it correctly
  for the first time in the harness's history.
* **Context-count discipline emerged as a real practice, not a one-off.** Sprint 3 was punished by a
  new cached context; by Sprint 5 the Generator was actively minimising them — routing binding checks
  to `ApplicationContextRunner`, and deliberately declaring a property set seven other classes already
  use so a `@SpringBootTest` reuses a cached context rather than adding one. A sprint needing three
  distinct property configurations added exactly one context. Worth writing into `how-to-test`.

## Token Cost Basis

`(total word count across read/written files) * 1.3 * iteration count`

| Group | Words |
| --- | --- |
| Harness artifacts — `spec.md`, `sprint-5-contract.md`, `generator-summary.md`, `evaluator-feedback.md`, `sprint-4-run-log.md`, 4 agent files, 7 skill files, `CLAUDE.md` | 13,258 |
| Source written — `SlaAlertProperties`, `AlertEventListener`, `application.yml` | 2,005 |
| Tests written — 3 new test classes plus `AlertEventListenerTest` | 2,903 |
| Read for context — `NotificationService`, `Notification`, `FakeNotificationRepository`, `SlaBreachAlertingIntegrationTest` | 1,237 |
| **Total words** | **19,403** |

`19,403 * 1.3 * 1 = 25,224` → **~25.2k tokens**

Third consecutive decline (29.6k → 26.7k → 25.2k), as each sprint needed less of `src/` read to
orient. Excludes Maven output, which was filtered rather than read, and the four verification runs
(two mutation probes and two post-restore full builds).

**Feature total: `29.6k + 26.7k + 25.2k` ≈ 81.5k tokens across three sprints.** For comparison, shift
handover cost ~39.5k across two. Per sprint the two features are comparable (~27k versus ~20k), with
this feature's premium explained by its planning overhead landing in Sprint 3, three modules touched
rather than one, and six new test classes rather than two.

## Feature Retrospective — SLA Breach Alerting

Delivered against the original request in full: HIGH and CRITICAL activities past due and not `DONE`
raise one `SLA_BREACH` to the assignee's Department Lead, automatically on a 15-minute sweep, and
escalate once to the store's `STORE_MANAGER` after a configurable grace period defaulting to four
hours.

Built with **no new event, no new entity, no schema change, no new endpoint, no new error code and no
new enum value.** The whole feature rests on one design decision taken at planning time: the sweep
re-publishes `TaskOverdueEvent` on every cycle rather than tracking what it has reported, so the
event's arrival *is* the proof the activity is still unresolved, and the existing notification rows
(`source_ref`, `alert_type`, `created_at`, `subject`) serve as the state machine. That kept
`activities` free of alerting policy and `alerts` free of activity state, and it is why the module
boundaries never came under pressure.

Two behaviours a reader of the original request might expect are **not** present, both declared rather
than quietly omitted: alerts are created `PENDING` and nothing calls `markSent`, so "fire a
notification" means raised and readable through the API, not delivered; and nothing retracts or
acknowledges an alert once the activity reaches `DONE`, so the rows persist. Neither was requested.
Both are reasonable candidates for a follow-up feature — the second in particular would need an
`alerts` consumer for `TaskStatusChangedEvent`, which is a sprint of its own.

Two items now need a human, since no sprint follows: the `PT4H` alert-body string above, and the
documentation drift list — of which `app-context` §3's endpoint count is the priority, because it
demonstrably propagated a wrong assertion into a sprint contract.
