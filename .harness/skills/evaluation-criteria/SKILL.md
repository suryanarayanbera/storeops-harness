# StoreOps Evaluation Criteria

The rubric the Evaluator scores against. The review *method* — order of work, detection, feedback
shape — is in [how-to-review](../how-to-review/SKILL.md).

Two separate outputs come out of a review, and confusing them is how leniency gets in:

- **The verdict** is decided by hard gates alone. Every gate is binary, and the same check results
  always produce the same verdict. Weights never soften a gate.
- **The score** is the weighted sum below, recorded in the run log so the Monitor can see quality
  drift across sprints. A sprint can score 88 and still FAIL.

## 1. Dimensions

| # | Dimension | Weight | Automated hard gate |
| --- | --- | --- | --- |
| A | Contract fulfilment | 40% | `./mvnw clean test` — JUnit, exit 0 |
| B | Architectural compliance | 35% | `ModuleBoundaryTest` (12 ArchUnit rules) + Checkstyle `IllegalThrows` / `NoRawErrorThrows` / `IllegalCatch` |
| C | Test quality | 25% | `jacoco:check` — bundle line ≥ 85%, branch ≥ 60%; per-class line ≥ 70%, branch ≥ 50% on services and listeners |

100% total. All three gates run inside `./mvnw clean test`, so one command settles the automated half
of every dimension. A non-zero exit is a FAIL before any judgement is applied.

Weights follow consequence. A sprint that doesn't deliver its contract is worthless whatever its
shape, so A leads. B is why the harness exists — it is the four failure modes the standards team
blocked the rollout over. C is last but material, because a passing suite that asserts nothing is how
those failure modes reached `main` in the first place.

## 2. Dimension A — Contract fulfilment (40%)

Hard gates:

- `./mvnw clean test` exits 0. Compilation, all tests, every other gate below.
- Every acceptance criterion in the sprint contract has at least one test asserting its THEN.
- No acceptance criterion is silently dropped. A criterion the Generator chose not to implement is a
  FAIL even if `generator-summary.md` declares it as a known gap.

Scored below the gates: whether the implementation matches the criterion's *intent*, error codes are
the ones the contract named, and edge cases the contract specified (partial failure, idempotency) are
handled as written.

## 3. Dimension B — Architectural compliance (35%)

`ModuleBoundaryTest` is the project's dependency analyser and it is authoritative. A green build has
already proved, deterministically:

| Constraint | Rules |
| --- | --- |
| No cross-module repository imports | `noCrossModuleRepositoryImports`, `repositoriesAreReachedOnlyFromServices` |
| No circular module dependencies | `modulesAreFreeOfCycles` |
| Cross-module effects via the event bus | `sideEffectsCrossBoundariesOnlyViaTheEventBus`, `eventsDoNotLeakModuleTypes` |
| `reports` writes nothing elsewhere | `reportsReadsThroughServicesOnly`, `reportsTouchesOnlyServiceAndDomainOfOtherModules` |
| Layer separation | `layersAreRespected`, `repositoriesAreFreeOfHttpConcerns`, `routesDoNotReachRepositories` |
| No raw throws | `noGenericExceptionsAreThrown`, `everyAppErrorSubtypeLivesInSharedError` |

Checkstyle covers the error contract on the source text, tests included.

The LLM-assessed hard gates are the ones no rule can see:

- **A required event was never published.** If the contract changes state another module must react
  to and no event is raised, FAIL. Import analysis cannot detect an absence.
- **Event wiring that fails silently** — publisher not `@Transactional`, listener missing
  `@TransactionalEventListener(AFTER_COMMIT)` or `@Transactional(REQUIRES_NEW)`, or the
  `ErrorHandler` bean dropped from `EventBusConfiguration`.
- **Business logic in a route.** Enum conditionals, SLA arithmetic, status-transition validation or
  partial-failure aggregation in a `@RestController`. `@Valid` is not business logic.

## 4. Dimension C — Test quality (25%)

This dimension exists for one failure mode: tests that assert HTTP status codes and verify no
business rule.

Hard gates:

- `jacoco:check` passes. Thresholds are a ratchet at the current baseline, so new untested code
  fails the build even when the project average stays healthy.
- Every new or changed service method has a negative case: the rejected payload, the unknown id, the
  forbidden transition.
- No acceptance criterion is covered *only* by a status-code assertion. The test must assert the
  observable outcome — persisted state, the published event and its payload, or the error `code`.
- Every absence assertion has a positive counterpart in the same class. `isEmpty()` and
  `hasSize(before)` both pass when the mechanism under test is entirely broken.
- New event or listener means a new case in `EventDeliveryIntegrationTest` asserting the side effect
  happened.

Scored below the gates: whether tests sit at the right layer, and whether `@DisplayName` states the
rule rather than the method name.

## 5. Verdict rules

Apply in order. Stop at the first that matches.

1. Any automated gate non-zero → **FAIL**.
2. Any LLM-assessed hard gate in §2–§4 violated → **FAIL**.
3. All gates pass, and remaining findings cannot change behaviour — a dead private method, a stale
   comment, a `@DisplayName` that names a method → **CONDITIONAL PASS**, listing each cleanup.
4. Otherwise → **PASS**.

Two rules keep this deterministic:

- **Ambiguity is a FAIL.** If you cannot establish whether a gate was violated, fail and state
  exactly what you could not determine. Never resolve doubt in the Generator's favour.
- **Judge the diff.** Only the files `generator-summary.md` declares are in scope. Baseline gaps are
  documented in [app-context](../app-context/SKILL.md) §7 and are not this sprint's regression.

Record the verdict, the per-dimension score and every gate result — pass or fail — so the run log
shows which gate caught what, and which dimension is trending down.
