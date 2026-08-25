# Generator Summary — Sprint 4: SLA Breach Routed to the Department Lead

`mvn clean test` exit 0. JUnit 181/181 (154 at Sprint 3 close, +27), ArchUnit 12/12, Checkstyle 0
violations, SpotBugs clean, JaCoCo bundle and per-class gates met.

## 1. AC Self-Check

| AC | Criterion (short) | Met | Test proving it |
| --- | --- | --- | --- |
| 1 | Breach alerts the assignee's Department Lead, not the assignee; full field assertion | yes | `AlertEventListenerTest.breachAlertsTheDepartmentLeadNotTheAssignee` |
| 2 | Repeat sweep raises nothing; original row keeps its id and createdAt | yes | `AlertEventListenerTest.repeatSweepRaisesNothing` |
| 3 | De-duplication keys on the activity, not the alert type | yes | `AlertEventListenerTest.aDifferentActivityIsNotSuppressed` |
| 4 | MEDIUM and LOW raise nothing | yes | `AlertEventListenerTest.untrackedPrioritiesRaiseNothing` |
| 5 | No Department Lead in the store falls back to the Store Manager | yes | `AlertEventListenerTest.noDepartmentLeadFallsBackToTheStoreManager` |
| 6 | Null, blank and unknown assignee each reach the Store Manager, none throws | yes | `AlertEventListenerTest.undeterminableDepartmentReachesTheStoreManager` |
| 7 | No resolvable recipient is silent, no `ValidationError` escapes | yes | `AlertEventListenerTest.noResolvableRecipientIsSilent` |
| 8 | Inactive and wrong-department leads skipped; fallback correct once the active lead is gone | yes | `AlertEventListenerTest.inactiveAndWrongDepartmentLeadsAreSkipped`, `AlertEventListenerTest.withoutTheActiveLeadTheFallbackIsTheStoreManager` |
| 9 | Two candidates resolve to the lowest id, independent of roster order | yes | `AlertEventListenerTest.twoCandidateLeadsResolveToTheLowestId`, `AlertEventListenerTest.candidateOrderDoesNotChangeTheOutcome` |
| 10 | End to end after commit through the real bus and H2; assignee not notified; second sweep no duplicate | yes | `SlaBreachAlertingIntegrationTest.sweepDeliversBreachToTheDepartmentLead`, `SlaBreachAlertingIntegrationTest.theAssigneeIsNotNotified`, `SlaBreachAlertingIntegrationTest.aSecondSweepRaisesNoDuplicate` |
| 11 | Removing the publisher's transaction breaks a test | yes | mutation probe run and recorded below |

## 2. Scenario 11 — Mutation Probe Result

`@Transactional` was deleted from `TaskService.publishOverdueBreaches()` and the suite re-run:

```
[INFO] Tests run: 17, Failures: 0 -- TaskServiceTest
[INFO] Tests run: 14, Failures: 0 -- AlertEventListenerTest
[ERROR] Tests run: 4, Failures: 2 -- SlaBreachAlertingIntegrationTest
[ERROR]   SlaBreachAlertingIntegrationTest.sweepDeliversBreachToTheDepartmentLead:71
          JSON path "$" Expected: a collection with size <2>
[ERROR]   SlaBreachAlertingIntegrationTest.aSecondSweepRaisesNoDuplicate:92
          JSON path "$" Expected: a collection with size <2>
```

This is exactly the split Sprint 3's run log predicted. All 31 unit tests stay green — the service
tests observe the bus through `RecordingEventBus`, which records at publish time, and the listener
tests call the handler directly — while the integration tests fail because no alert is ever raised.
The annotation was restored and the full suite re-run to 181/181, exit 0.

One thing the probe exposed that is worth recording: `theAssigneeIsNotNotified` and
`onlyTheTrackedBreachRaisesAnAlert` **also stayed green** without the annotation, because both assert
an absence. An absence assertion cannot detect a dropped transaction — everything is absent. Only the
two tests asserting a positive row bit. That is the concrete case for the rubric's rule about absence
assertions needing a positive counterpart.

## 3. Files Changed

### alerts — listener
- `listener/AlertEventListener.java` — `onTaskOverdue()` rewritten. Added `SLA_BREACH_SUBJECT`
  constant, `UserService` injected, and three private helpers: `resolveBreachRecipient`,
  `departmentOf`, `storeManagerId`. De-duplication reads existing `SLA_BREACH` rows by `sourceRef`.
  `onTaskStatusChanged()` untouched. Class javadoc corrected: it previously claimed only
  `shared.events` types were imported, which stopped being true when `staff` was injected.

### alerts — service
- `service/NotificationService.java` — added `findBySourceRefAndAlertType(String, AlertType)`,
  delegating to the repository, oldest first. The listener's de-duplication read; keeps the listener
  off the repository, which ArchUnit rule 1b forbids.

### alerts — repository
- `repository/NotificationRepository.java` — added `findBySourceRefAndAlertType`.
- `repository/NotificationJpaRepository.java` — added `OLDEST_FIRST` sort constant and a derived
  `findBySourceRefAndAlertType(String, AlertType, Sort)` query.
- `repository/JpaNotificationRepository.java` — implemented it; returns empty for a null argument
  rather than issuing a query that cannot match.

### staff — service
- `service/UserService.java` — added `findByStoreIdAndRole(String, StaffRole)`: active only, role
  matched exactly, sorted by id, empty for null arguments. Implemented by filtering
  `findByStoreId`; `UserRepository` was **not** widened and no mutator was added.

### activities
- `service/TaskService.java` — no change beyond the Scenario 11 probe, which was reverted. Verified
  `@Transactional` present at `TaskService:176` in the final state.

### tests — new
- `SlaBreachAlertingIntegrationTest.java` — **new**, 4 tests. `@SpringBootTest` with the sweep
  disabled and `@DirtiesContext(BEFORE_EACH_TEST_METHOD)`. Every assertion reads a `Notification`
  back through `GET /api/notifications`.
- `alerts/service/NotificationServiceTest.java` — **new**, 8 tests. See Deviation 2.
- `staff/service/UserServiceTest.java` — **new**, 7 tests. Covers the active filter, exact role
  match, id ordering and null handling of `findByStoreIdAndRole`.
- `support/FakeUserRepository.java` — **new** fixture, with a `withSeedRoster()` builder. Deliberately
  does **not** filter inactive staff, matching `JpaUserRepository`, so a missing `active` filter in
  the service is exposed rather than hidden.

### tests — modified
- `alerts/listener/AlertEventListenerTest.java` — 6 tests → 14. Constructor updated for the new
  collaborator. Two tests asserted the old stub behaviour and were rewritten:
  `criticalOverdueRaisesSlaBreach` (expected the assignee as recipient and a priority-bearing subject)
  and `overdueUnassignedRaisesNothing` (expected silence, where the contract now requires a Store
  Manager fallback). The three `onTaskStatusChanged` tests are unchanged.
- `support/FakeNotificationRepository.java` — implemented the new repository method, sorted oldest
  first to match the real query rather than relying on insertion order.
- `ApiSmokeTest.java` — added `@DirtiesContext(AFTER_CLASS)`. This is Sprint 3's outstanding cleanup;
  see Deviation 1.

### untouched
`TaskOverdueEvent` and every other `shared` type, `EventBusConfiguration`, `NotificationEntity`,
`UserRepository`, `UserEntity`, `data.sql`, `pom.xml`, all `routes` classes. No new event, no new
`AppError` subtype, no new enum value, no schema change, no new endpoint. No `activities` import in
`alerts`, and no `TaskService` injected into `alerts`.

## 4. Deviations From The Contract

**1. Sprint 3's outstanding cleanup was collected, and with a different fix than suggested.** The
Sprint 3 run log assigned the `ApiSmokeTest` leak to this sprint. The suggested fix was
`@DirtiesContext(BEFORE_EACH_TEST_METHOD)`, matching its `BulkStatus*` siblings; I used
`AFTER_CLASS` instead. Same outcome for every downstream class, at one context rebuild rather than
eleven.

Verified rather than assumed: with the annotation in place I temporarily restored the original
`SELECT count(*) FROM projects` table-total assertion in `H2SchemaTest` and the full suite passed
181/181, proving the leak is closed at source. I then restored the id-keyed version, because that
assertion holds however many contexts the suite grows, whereas the fix depends on one annotation on
one class staying put. Both defences now hold; the reasoning is recorded in the test's javadoc.

**2. One test class beyond the contract: `NotificationServiceTest`.** After the contract's work was
complete, `NotificationService` sat at **50% branch coverage — exactly on the JaCoCo floor** — with no
test class of its own, and its `ValidationError` guard on `raise` was entirely unexercised. Two
reasons to fix that here rather than leave it: `how-to-test` §3 requires a negative test for every new
service method, and a class sitting exactly on a threshold fails the build on the next uncovered
branch anyone adds. Sprint 5 extends this listener. Coverage moved 50% → 100% branch, 72% → 100% line.

**3. `UserService` is a real service over a fake repository in the listener test, not a Mockito
mock.** `how-to-test` §2 says to use Mockito for a read from another module. I used
`FakeUserRepository` with a real `UserService`. ACs 8 and 9 test roster-dependent selection — skip
the leaver, skip the wrong department, lowest id of two candidates — so stubbing
`findByStoreIdAndRole` would assert my stub's ordering rather than the system's, which is the weak
test the rubric warns against. The contract's own wording ("GIVEN a roster for `store-001`
containing…") points the same way, as does the same skill's "Use Fakes, Not Mocks" headline. The
filter and sort logic also has its own direct coverage in `UserServiceTest`.

**4. `./mvnw` still does not exist.** Unchanged from Sprint 3 and still routed to the Planner. Used
`mvn clean test`.

## 5. Known Gaps

**In scope and complete.** No contract criterion was dropped, and Sprint 3's carried item (the
unproven after-commit path) is now closed by Scenario 11's probe.

Deliberately out of scope, carried to Sprint 5:

1. **No grace period and no escalation to `STORE_MANAGER`.** A repeat observation is currently
   suppressed and nothing more — the branch Sprint 5 extends into its second stage. The
   `storeops.alerts.sla.grace-period` property does not exist yet.
2. **`AlertType.ESCALATION` collision is not yet guarded.** Sprint 5 introduces
   `SLA_ESCALATION_SUBJECT` and the subject-plus-type check. Nothing in this sprint depends on it,
   because stage two does not exist, but Sprint 5's Scenario 6 is the test that must bite.
3. **`AlertEventListener` is at 21/22 branches (95%).** The uncovered branch is the blank-assignee
   arm of the pre-existing `isUnassigned` helper used by `onTaskStatusChanged` — baseline code this
   sprint did not touch. Well clear of the 50% floor; noted so the number is not mistaken for
   something this sprint introduced.
