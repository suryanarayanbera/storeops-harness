# Generator Summary — Sprint 1: Template Endpoint And Department Resolution

`./mvnw clean test` — **BUILD SUCCESS**. 275 tests, 0 failures, 0 errors. Checkstyle 0 violations,
SpotBugs `BugInstance size is 0`, `jacoco:check` passed, all 12 `ModuleBoundaryTest` rules passed.

## 1. AC self-check

| AC | Criterion (short) | Met | Test proving it |
| --- | --- | --- | --- |
| 1 | 202 + taskCount 4 + assignments, event with 4 resolved items, no activity created | yes | `ProjectServiceTest.applyTemplateResolvesCoveredDepartments`, `ProjectServiceTest.applyTemplatePublishesResolvedLines`, `PlanogramTemplateIntegrationTest.applyingTheTemplateCreatesNoActivityYet` |
| 2 | DEPARTMENT_LEAD wins over an ASSOCIATE in the same department | yes | `ProjectServiceTest.applyTemplatePrefersDepartmentLead`, `PlanogramTemplateIntegrationTest.theDepartmentLeadOnProjectOneTakesTheGroceryLines` |
| 3 | Lowest staff id breaks a tie; identical across two calls | yes | `ProjectServiceTest.applyTemplateBreaksTiesOnLowestStaffId` |
| 4 | An inactive member is not eligible; still 202 | yes | `ProjectServiceTest.applyTemplateSkipsInactiveMembers` |
| 5 | Unknown programme → 404 `PROJECT_NOT_FOUND`, no event | yes | `ProjectServiceTest.applyTemplateRejectsUnknownProgramme`, `PlanogramTemplateIntegrationTest.aRejectedRequestDeliversNoEvent` |
| 6 | Closed programme → 409 `PROGRAMME_CLOSED` naming the id, no event | yes | `ProjectServiceTest.applyTemplateRefusesClosedProgramme`, `PlanogramTemplateIntegrationTest.aClosedProgrammeIsRefused` |
| 7 | Unknown template id → 400 with details naming `templateId` and the value | yes | `ProjectServiceTest.applyTemplateRejectsUnknownTemplate`, `ProjectRoutesTest.applyTemplateSurfacesUnknownTemplate` |
| 8 | Blank template id → 400 from bean validation, service never entered | yes | `ProjectRoutesTest.applyTemplateRejectsBlankTemplateId`, `PlanogramTemplateIntegrationTest.aBlankTemplateIdIsRejected` |
| 9 | Unknown `requestedBy` → 400 with details, no event | yes | `ProjectServiceTest.applyTemplateRejectsUnknownRequester` |
| 10 | Omitted `requestedBy` recorded as `api` | yes | `ProjectServiceTest.applyTemplateDefaultsRequestedByToApi`, `PlanogramTemplateIntegrationTest.anOmittedRequesterIsRecordedAsApi` |
| 11 | Event reaches an AFTER_COMMIT subscriber, proving the publisher is transactional | yes | `PlanogramTemplateIntegrationTest.theTemplateEventIsDeliveredAfterCommit` |
| 12 | A failing subscriber does not fail the caller | yes | `PlanogramTemplateIntegrationTest.aFailingSubscriberIsContained` |

AC 8 is asserted twice on purpose. The routes slice proves the service is never called
(`verify(projectService, never())`); the integration test posts a blank id at a **non-existent**
programme and still gets 400 rather than 404, which is the same claim made where it cannot be faked
by a mock.

Tests written beyond the contract, all in `ProjectServiceTest` unless noted:
`applyTemplateConsidersProgrammeMembersOnly` (a store's department lead who is not on the programme
stays ineligible — the rule the contract states but no criterion pins),
`applyTemplateTrimsTemplateId`, `applyTemplateWritesNothingToTheProgramme`, and
`ProjectRoutesTest.applyTemplateRejectsMissingTemplateId` / `applyTemplateSurfacesUnknownProgramme` /
`applyTemplateSurfacesClosedProgramme`.

## 2. Files changed

### shared
- `events/TemplateTaskDefinition.java` — **new.** One resolved activity to create. All-`String`
  fields, so `shared` gains no dependency on `activities.domain`.
- `events/ProgrammeTemplateRequestedEvent.java` — **new.** `eventType()` `PROGRAMME_TEMPLATE_REQUESTED`;
  carries `projectId`, `storeId`, `templateId`, `requestedBy`, `items`, `occurredAt`. `items`
  defensively copied in the compact constructor.

### programmes
- `domain/PlanogramTemplateItem.java` — **new.** One catalogue line: title, description, department,
  priority-as-String.
- `domain/PlanogramTemplate.java` — **new.** The `PLANOGRAM_STANDARD` catalogue (4 items), plus
  `findById` returning `Optional` and `knownIds()` for the error detail.
- `dto/ApplyTemplateRequest.java` — **new.** `templateId` (`@NotBlank`), optional `requestedBy`.
- `dto/TemplateAssignmentResponse.java` — **new.** One echoed line: title, department, priority,
  nullable assignee.
- `dto/ApplyTemplateResponse.java` — **new.** `projectId`, `templateId`, `taskCount`, `assignments`.
- `service/ProjectService.java` — added `@Transactional applyTemplate(String, ApplyTemplateRequest)`,
  publishing `ProgrammeTemplateRequestedEvent`; private helpers `resolveRequestedBy`,
  `resolveAssignee`, `worksIn`, and the private records `ResolvedItem` and `Candidate`.
- `routes/ProjectRoutes.java` — added `POST /{id}/templates`, answering `202` via
  `ResponseEntity.accepted()`. Mapping and status only.

### tests
- `support/RecordingTemplateSubscriber.java` — **new.** AFTER_COMMIT recorder for the new event.
- `support/FailingTemplateSubscriber.java` — **new.** AFTER_COMMIT subscriber that always throws,
  with an invocation counter so a containment test cannot pass vacuously.
- `programmes/service/ProjectServiceTest.java` — 13 new tests for `applyTemplate`; existing 6
  unchanged in substance. **See the deviation below.**
- `programmes/routes/ProjectRoutesTest.java` — 6 new tests for the new endpoint.
- `PlanogramTemplateIntegrationTest.java` — **new.** 9 tests: `@SpringBootTest` with the SLA sweep
  disabled and `@DirtiesContext(BEFORE_EACH_TEST_METHOD)`, following `RegionalRollupIntegrationTest`.

No change to `data.sql`, no new JPA entity, no schema change — `H2SchemaTest` is untouched and passes.

## 3. Deviation from the existing code, declared

`ProjectServiceTest` previously took `UserService` as a Mockito mock with a single
`when(userService.exists("user-002"))` stub. Department resolution reads
`User.profile().department()` and `User.active()` per member, so proving ACs 2, 3 and 4 over a mock
would have meant stubbing `findById` three or four times per test. I replaced the mock with a real
`UserService` over `FakeUserRepository().withSeedRoster()`, which already carries the seed's real
roles, stores and departments.

`how-to-test` §2 prefers fakes and permits Mockito for a cross-module read, so both forms are
sanctioned; I switched for legibility, not compliance. All six pre-existing tests keep their original
assertions and pass unchanged — `user-002` exists in the roster and `user-999` does not, which is
what those two tests needed from the mock.

## 4. Known gaps

None. All 12 criteria are implemented and asserted.

Two consequences of the design that are intended rather than incomplete, restated so the Evaluator
does not read them as omissions:

* **No activity exists when this endpoint returns.** `applyTemplate` creates nothing and must not;
  `activities` owns `Task`. `PlanogramTemplateIntegrationTest.applyingTheTemplateCreatesNoActivityYet`
  asserts `store-002` still holds only `task-004`. That expectation flips to five in Sprint 2, and the
  flip is the proof the listener works.
* **`taskCount` is the template expansion, not a created-row count.** Documented on
  `ApplyTemplateResponse`, and the reason the endpoint is `202` with no `Location` header.
