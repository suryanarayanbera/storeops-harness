# How to Review a StoreOps Sprint

The rubric — gates, verdicts, definition of done — lives in
[evaluation-criteria](../evaluation-criteria/SKILL.md). This file is the method: what to check, in
what order, and how to write it up.

## 1. Scope

Review only the files `generator-summary.md` lists, plus their tests. Check that list against
`git status --porcelain`; an undeclared change is itself a finding. Leave untouched code alone — the
baseline gaps in [app-context](../app-context/SKILL.md) §7 are deliberate, not this sprint's problem.

## 2. Locate build failures

`./mvnw clean test` can fail in six places. Work out which before writing anything:

| Output | Gate | Report |
| --- | --- | --- |
| `[IllegalThrows]`, `[NoRawErrorThrows]` | Checkstyle, at `validate` (before compile) | The error-contract violation at the `file:line` given |
| `BugInstance` | SpotBugs, at `test-compile` | Pattern and class. Suppressions stay scoped, never blanket |
| `ModuleBoundaryTest` | ArchUnit (§3) | The rule name and the class it lists |
| `Rule violated for class` | JaCoCo `check`, at `test` | The class, its ratio, and the floor it missed |
| Test failure | JUnit | The assertion, and whether an AC is now unmet |
| Compilation error | javac `-Xlint:all` | Verbatim |

Quote the real output. The Generator needs the literal `file:line` to fix it in one pass.

## 3. What the build already checked

A green build means these twelve ArchUnit rules passed. Don't re-audit them by eye, and don't raise a
finding one of them would have caught:

| Constraint | Rules in `ModuleBoundaryTest` |
| --- | --- |
| Cross-module repository imports | `noCrossModuleRepositoryImports`, `repositoriesAreReachedOnlyFromServices` |
| Circular dependencies | `modulesAreFreeOfCycles` |
| Event bus for cross-module effects | `sideEffectsCrossBoundariesOnlyViaTheEventBus`, `eventsDoNotLeakModuleTypes` |
| `reports` read-only | `reportsReadsThroughServicesOnly`, `reportsTouchesOnlyServiceAndDomainOfOtherModules` |
| Layer separation | `layersAreRespected`, `repositoriesAreFreeOfHttpConcerns`, `routesDoNotReachRepositories` |
| Error contract | `noGenericExceptionsAreThrown`, `everyAppErrorSubtypeLivesInSharedError` |

## 4. What it doesn't

Five things survive a green build. They're the review.

**Missing events.** ArchUnit checks imports, not that anything was published. If the contract changes
state another module reacts to, find the publish call and check the payload.

**Silent wiring.** On a new publisher or listener, check all three. Each fails with no exception, no
log, and a passing HTTP 200 test:

- publishing method `@Transactional`, or the listener never runs
- listener `@TransactionalEventListener(AFTER_COMMIT)` *and* `@Transactional(REQUIRES_NEW)`; without
  the second it runs but never flushes
- the `ErrorHandler` bean in `EventBusConfiguration`, or a subscriber bug fails the caller's request

**Weak tests.** `status().isOk()` says nothing about a business rule; look for persisted state, the
emitted event, the error `code`. Absence assertions are worse: `assertThat(notifications).isEmpty()`
passes happily when dispatch is broken end to end. Each one needs a presence assertion beside it.

**Logic in routes.** `routesDoNotReachRepositories` catches persistence access, not rules. Enum
conditionals, SLA arithmetic, status transitions, partial-failure aggregation: all service layer.
`@Valid` on the request is fine.

**Invented domain.** Check enum values, transitions and route bases against
[app-context](../app-context/SKILL.md) §4. Errors map to a specific `AppError` subtype, not
`InternalError` as a catch-all.

## 5. Writing a finding

Where, which rule, what to change:

```
FAIL  src/main/java/com/cognizant/storeops/activities/service/TaskService.java:112
      Rule: Cross-module writes via event bus only (architecture-principles §3)
      Fix:  Drop the injected NotificationService. Publish TaskStatusChangedEvent via
            EventBus.publish(...) and let AlertEventListener create the notification.
```

"The service has boundary issues and tests could be stronger" isn't a finding, it's a guessing game.

## 6. Calibration

- Can't tell whether a rule was broken? FAIL, and say what you couldn't determine.
- CONDITIONAL PASS is for cleanups that can't change behaviour. If a hard gate, a test or an AC is
  involved, it's a FAIL.
- Don't fail unscoped work or invent acceptance criteria. If the contract is the problem, say so, so
  the escalation names the real cause.
- Don't fix anything. If you're editing `src/`, you've stopped reviewing.
