# Sprint 1 Run Log

## Summary

| Field | Value |
| --- | --- |
| Sprint ID | 1 (first of two in the planogram task template feature) |
| Goal | `POST /api/projects/{id}/templates` validates the programme and template, expands the `PLANOGRAM_STANDARD` catalogue, resolves each line's department to a programme member, publishes `PROGRAMME_TEMPLATE_REQUESTED` and answers `202 Accepted`. Creates no activity |
| Modules touched | `programmes` (routes, service, domain, dto); `shared` (events) |
| Final verdict | CONDITIONAL PASS |
| Iterations used | 1 of 3 |
| Escalated | no |
| Estimated token cost | ~24.3k |

`mvn clean test` exit 0. JUnit 275/275 (247 before this sprint, +28), ArchUnit 12/12, Checkstyle 0
violations, SpotBugs `BugInstance size is 0`, JaCoCo bundle 93.5% line / 75.3% branch against floors
of 85% / 60%; `ProjectService` 98.6% line / 91.7% branch against the per-class floor of 70% / 50%.

**Feature in progress: 1 of 2 sprints closed. Sprint 2 (the `activities` listener) is next.**

## Iterations

| # | Verdict | Gate or rule that failed | Fix applied |
| --- | --- | --- | --- |
| 1 | CONDITIONAL PASS | none — six automated gates and six LLM-assessed hard gates passed on the first attempt | none required; four cosmetic cleanups left outstanding |

## Files Changed

### shared — events
- `events/TemplateTaskDefinition.java` — **new**. One resolved activity to create: `title`,
  `description`, `category`, `priority`, `assigneeId`, all `String`. Keeps `shared` free of any
  dependency on `activities.domain`, which ArchUnit rule 3b enforces.
- `events/ProgrammeTemplateRequestedEvent.java` — **new**. `eventType()`
  `PROGRAMME_TEMPLATE_REQUESTED`; carries `projectId`, `storeId`, `templateId`, `requestedBy`,
  `items`, `occurredAt`. `items` defensively copied in the compact constructor. This is the
  application's **fifth** event.

### programmes — domain
- `domain/PlanogramTemplateItem.java` — **new**. One catalogue line: `title`, `description`,
  `department`, `priority` as a `String` name.
- `domain/PlanogramTemplate.java` — **new**. The `PLANOGRAM_STANDARD` catalogue of four items, held
  as code rather than a table; `findById` returning `Optional`, `knownIds()` for the error detail,
  and the `CATEGORY` constant.

### programmes — dto
- `dto/ApplyTemplateRequest.java` — **new**. `templateId` (`@NotBlank`), optional `requestedBy`.
- `dto/TemplateAssignmentResponse.java` — **new**. One echoed line with a nullable assignee.
- `dto/ApplyTemplateResponse.java` — **new**. `projectId`, `templateId`, `taskCount`, `assignments`.

### programmes — service
- `service/ProjectService.java` — added `@Transactional applyTemplate(String, ApplyTemplateRequest)`,
  publishing `ProgrammeTemplateRequestedEvent`. Private helpers `resolveRequestedBy`,
  `resolveAssignee`, `worksIn`, plus the private records `ResolvedItem` and `Candidate`. Existing
  methods untouched.

### programmes — routes
- `routes/ProjectRoutes.java` — added `POST /{id}/templates` answering `202` via
  `ResponseEntity.accepted()`. Binding and status only; no business logic.

### tests
- `support/RecordingTemplateSubscriber.java` — **new**. AFTER_COMMIT recorder for the new event.
- `support/FailingTemplateSubscriber.java` — **new**. AFTER_COMMIT subscriber that always throws,
  with an invocation counter so a containment test cannot pass vacuously.
- `PlanogramTemplateIntegrationTest.java` — **new**. 9 tests, `@SpringBootTest` with the SLA sweep
  disabled and `@DirtiesContext(BEFORE_EACH_TEST_METHOD)`.
- `programmes/service/ProjectServiceTest.java` — 13 new tests. The `UserService` Mockito mock was
  replaced with a real service over `FakeUserRepository().withSeedRoster()`; the six pre-existing
  tests keep their assertions unchanged.
- `programmes/routes/ProjectRoutesTest.java` — 6 new tests.

No new JPA entity, no schema change, no `data.sql` change. `H2SchemaTest` untouched and passing.

## Conditional Pass Cleanups

Four, all non-behavioural. Full detail in `sprint-1-evaluator-feedback.md` §4.

1. **`programmes/routes/ProjectRoutes.java:51`** — javadoc tags the new handler `Endpoint 10`, which
   `reports/routes/ReportRoutes.java:31` already holds. Should be `Endpoint 12`. **Introduced by this
   sprint**; the cheapest of the four to close.
2. **`.harness/skills/app-context/SKILL.md:35` and `:76`** — the vocabulary record still says nine
   endpoints and three events; there are now twelve and five. See the trend note below: this is not a
   new debt.
3. **`.harness/output/generator-summary.md:63`** — declares 12 new `ProjectServiceTest` tests; there
   are 13. The count should reconcile with the 19 the build reports.
4. **`programmes/service/ProjectServiceTest.java:276`** — `containsOnlyNulls()` asserts all four
   lines, but only the two `GROCERY` lines carry the signal for the inactive-member rule. Still
   catches the regression; imprecise as written.

## Quality Trend Notes

* **Nine sprints, nine CONDITIONAL PASSes, no retries.** Every sprint across all three features has
  closed on attempt 1 of 3, and not one has closed on a clean PASS. The escalation ladder has never
  been exercised. Two readings, and they are not mutually exclusive: contracts are being written at a
  granularity the Generator can hit first time, and the Evaluator is consistently finding cosmetic
  residue. The gate discipline looks sound — the concern is the residue, below.

* **`app-context` drift is now flagged for the fourth consecutive sprint and remains open.** It was
  raised in `regional-rollup-report/sprint-1-run-log.md:149`, again in `sprint-2-run-log.md:118` as
  "the highest-priority documentation debt", again in `sprint-3-evaluator-feedback.md:111`, and again
  here. §5 still reads "Three events exist. This is the whole list" while five exist; §3 still says
  nine endpoints while twelve exist.

  This is the one trend that has operational teeth rather than being tidiness. Every agent in the
  harness is instructed that a name absent from `app-context` does not exist. The Sprint 2 Generator
  must subscribe to `PROGRAMME_TEMPLATE_REQUESTED`, an event that file denies the existence of. The
  contract names it in full, so Sprint 2 is not blocked — but the mechanism that is supposed to stop
  agents inventing vocabulary has now been wrong for four sprints, and each feature widens the gap.
  **Recommend closing it before Sprint 2 rather than after the feature.**

* **Cleanup carry-forward is the pattern, not the exception.** Four cleanups logged here, five carried
  through each of the regional rollup sprints with one closed. Nothing in the workflow routes a
  CONDITIONAL PASS cleanup back to a Generator — `CLAUDE.md` §3 sends both PASS and CONDITIONAL PASS
  straight to the next sprint — so the only way these close is a human picking them up. That is a gap
  in the harness itself rather than in any sprint's output, and it is why the same `app-context` item
  has survived four reviews that each correctly identified it.

* **No problem module.** `programmes` took the whole of this sprint and produced no boundary finding;
  the two-way import ban between `programmes` and `activities` held, which was the design's central
  risk. The event-wiring gate — historically the one that bites, per `coding-conventions` §4 — passed
  with the publisher's `@Transactional` asserted by a real after-commit delivery test rather than
  assumed.

## Token Cost Basis

`(11,628 + 7,078) × 1.3 × 1 = 24,318` ≈ **~24.3k**

* 11,628 words — harness files read or written: `spec.md`, both sprint contracts,
  `generator-summary.md`, `evaluator-feedback.md`, the four agent definitions, all seven `SKILL.md`
  files, `CLAUDE.md`.
* 7,078 words — source and test files written or read for context: the eight new production files,
  `ProjectService.java`, `ProjectRoutes.java`, the three test files, the two new subscribers, plus
  `FakeUserRepository.java` and `ModuleBoundaryTest.java` read to establish fixtures and rules.
* × 1.3 words-to-tokens, × 1 iteration.
