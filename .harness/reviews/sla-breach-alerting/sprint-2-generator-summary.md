# Generator Summary — Sprint 2: The Lead Alert and the Breach Episode

`./mvnw clean test` — **BUILD SUCCESS**, 134 tests, 0 failures, 0 errors. Checkstyle (validate),
SpotBugs (test-compile) and `jacoco:check` all ran and passed. Test count 120 → 134.

## 1. AC self-check

| AC | Criterion (short) | Met | Test proving it |
| --- | --- | --- | --- |
| 1 | First observation notifies the Department Lead, not the assignee; episode recorded | yes | `SlaBreachServiceTest.firstObservationNotifiesTheDepartmentLead` |
| 2 | Repeat observation raises nothing, moves only `lastSeenAt` | yes | `SlaBreachServiceTest.repeatObservationRaisesNothingAndOnlyMovesLastSeen` |
| 3 | An assignee who is a Department Lead is notified directly | yes | `SlaBreachServiceTest.assigneeWhoIsADepartmentLeadIsNotifiedDirectly` |
| 4 | No lead for the department → Store Manager, still `SLA_BREACH`; same with no department | yes | `SlaBreachServiceTest.withNoDepartmentLeadTheStoreManagerIsNotified`, `.assigneeWithNoDepartmentFallsBackToTheStoreManager` |
| 5 | An inactive Department Lead is skipped | yes | `SlaBreachServiceTest.inactiveDepartmentLeadIsSkipped` |
| 6 | Unresolvable assignee (unknown, null, blank) alerts nobody and records nothing | yes | `SlaBreachServiceTest.unresolvableAssigneeAlertsNobodyAndRecordsNothing` |
| 7 | Store with no lead and no manager is retried, not swallowed | yes | `SlaBreachServiceTest.storeWithNoLeadAndNoManagerIsRetriedRatherThanSwallowed` |
| 8 | Priority outside the SLA bands is ignored; HIGH and CRITICAL are not | yes | `SlaBreachServiceTest.priorityOutsideTheSlaBandsIsIgnored` |
| 9 | The staff read returns only the matching store and role | yes | `UserServiceIntegrationTest.findByStoreIdAndRoleFiltersOnBothCriteria`, `.findByStoreIdAndRoleCarriesTheProfileAndToleratesNoMatch` |
| 10 | The sweep reaches the lead end to end, once across two sweeps | yes | `EventDeliveryIntegrationTest.sweptOverdueBreachReachesTheDepartmentLeadExactlyOnce` |
| 11 | Schema and seed script still agree | yes | `H2SchemaTest.schemaHasExpectedTables`, `.slaBreachesTableShape`, `.seedDataLoaded` |

Beyond the contract, two tests cover guardrails that had no criterion of their own:
`SlaBreachServiceTest.tiedLeadsResolveToTheLowestId` (deterministic tie-break) and
`AlertEventListenerTest.repeatedOverdueObservationRaisesOneAlert` (the handler delegates rather than
deciding, so dedup holds through it).

## 2. Files changed

### alerts — source
- `domain/SlaBreach.java` — **new** record: `taskId`, `storeId`, `priority`, `firstBreachAt`,
  `leadRecipientId`, `leadNotifiedAt`, `lastSeenAt`, plus an `opened(...)` factory and `withLastSeen`.
  No escalation fields — those are Sprint 3, so nothing here is unused
- `repository/SlaBreachEntity.java` — **new** `@Entity @Table("sla_breaches")`, `@Id` on `task_id`,
  `fromDomain`/`toDomain` in the `NotificationEntity` style. `priority` is a plain string column, not
  `@Enumerated`: the value arrives as a string and alerts never imports `TaskPriority`
- `repository/SlaBreachJpaRepository.java` — **new**, package-private, `DEFAULT_SORT` oldest breach first
- `repository/SlaBreachRepository.java` — **new** port: `save`, `findByTaskId`, `findAll`
- `repository/JpaSlaBreachRepository.java` — **new** adapter; null `taskId` returns empty
- `service/SlaBreachService.java` — **new.** All the policy: priority filter, episode lookup, the
  lead → store-manager fallback chain, and the `SLA_BREACH` alert. Takes `TaskOverdueEvent` directly
- `listener/AlertEventListener.java` — `onTaskOverdue` reduced to `slaBreachService.observe(event)`;
  `SLA_TRACKED_PRIORITIES` moved to the service; constructor now
  `(NotificationService, SlaBreachService)`. `onTaskStatusChanged` untouched, both annotations intact

### staff — source
- `repository/UserRepository.java` — new read `findByStoreIdAndRole(String, StaffRole)`
- `repository/UserJpaRepository.java` — derived query `findByStoreIdAndRole(String, StaffRole, Sort)`
- `repository/JpaUserRepository.java` — adapter; null store or null role returns an empty list
- `service/UserService.java` — same read, delegating. Still no mutator of any kind

### resources
- `src/main/resources/data.sql` — comment recording that `sla_breaches` is intentionally unseeded, and
  why seeding it would suppress the alert for `task-001`

### tests
- `alerts/service/SlaBreachServiceTest.java` — **new**, 10 tests. Fakes for both repositories, a mocked
  `UserService` (another module — the one case `how-to-test` sanctions), fixed clocks. Time advances by
  rebuilding the service over the same repositories, which is what proves the episode is persisted
  rather than held in the service
- `staff/service/UserServiceIntegrationTest.java` — **new** `@SpringBootTest`, 2 tests, against the real
  H2 schema
- `alerts/listener/AlertEventListenerTest.java` — new constructor with the real `SlaBreachService` over
  fakes; `criticalOverdueRaisesSlaBreach` now also asserts the episode row; added
  `repeatedOverdueObservationRaisesOneAlert`. The `BLOCKED`, `DONE`, unassigned and LOW-priority tests
  are unchanged and still pass
- `EventDeliveryIntegrationTest.java` — Sprint 1's `sweptOverdueBreachReachesAlertsModule` replaced by
  `sweptOverdueBreachReachesTheDepartmentLeadExactlyOnce`: two sweeps, one alert for `user-003`, none
  for `user-004`, one `sla_breaches` row. Reads the table through `JdbcTemplate` so the test imports no
  repository
- `H2SchemaTest.java` — `SLA_BREACHES` added to the table list; new `slaBreachesTableShape` asserting
  the seven columns and that `TASK_ID` is the sole primary key
- `support/FakeSlaBreachRepository.java` — **new** test double over `FakeRepository`

No `UserRepository` test double exists in `support/` — staff is mocked wherever it is read — so the
contract's "any `UserRepository` test double must implement the new read" had nothing to update.

Nothing in `activities`, `programmes`, `reports` or `shared` was touched. No new event type, no new
`AppError` subtype, no new `AlertType` value, no route.

## 3. Known gaps

- **`SlaBreachRepository.findAll()` has no production caller.** It is in the contract's deliverable list
  and the adapter implements it, but only tests call it; the service reads by `taskId`. Sprint 3 needs
  `deleteByTaskId`, not `findAll`, so unless the breach-tracker endpoint that `spec.md` lists as out of
  scope arrives, this stays unused API surface. Kept rather than dropped because the contract specified
  it — flagging rather than silently deviating.
- **Nothing asserts the `WARN` log lines** on the unresolvable-recipient paths. The observable outcome
  (no alert, no row) is asserted instead; the log text is not.
- **The escalation half is absent by design.** A breach that stays unresolved is currently observed
  forever with `lastSeenAt` moving and nothing else happening. Sprint 3 adds the grace period, the
  `ESCALATION` to the store manager, and episode closure on `DONE`.
- **Sprint 1 cleanups carried, not fixed.** `TaskJpaRepository` still defines "overdue" separately from
  `Task.isOverdueAt`, and `OverdueSweepScheduler` still logs at `INFO` on empty sweeps. Both are in
  `activities`, which this sprint's contract does not open.
