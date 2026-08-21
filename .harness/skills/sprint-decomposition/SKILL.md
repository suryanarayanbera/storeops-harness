# Decomposing a StoreOps Feature

How to cut a feature request into sprints, and how to write criteria the Evaluator can settle without
asking you. The contract file format is in
[planner.agent.md](../../agents/planner.agent.md); this is the judgement behind it.

## 1. Where the boundaries go

Every sprint has to end with `./mvnw clean test` green. That single rule decides most cuts.

Cut vertically, one module at a time. A sprint that delivers only a repository has nothing an
acceptance criterion can observe, so route, service and repository for one capability travel together.
Cut across modules, never through them: if a feature touches `activities` and `alerts`, that is two
sprints, with the event contract as the seam. The publishing sprint is still testable on its own —
`RecordingEventBus` proves the event was raised with the right payload, whether or not anything
listens yet.

Size it at one endpoint, or one event chain. More than about five criteria, or more than two modules,
and it wants splitting. Don't split a refactor away from the feature that needs it; a sprint whose
only deliverable is groundwork can't be judged.

Later sprints may build on earlier ones. Never the reverse.

## 2. Worked example — shift handover bulk update

| Sprint | Delivers | Ends green because |
| --- | --- | --- |
| 1 | `PATCH /api/activities/bulk-status` in `activities`: route, service rule, partial-failure result | Route slice and service tests cover accepted, rejected and mixed batches |
| 2 | `SHIFT_HANDOVER` alerts in `alerts`, from the events sprint 1 publishes | Listener test covers the decision, integration test covers delivery |

Two sprints, not one, because a FAIL in the alerting decision shouldn't reopen the endpoint's
contract. Not three, because the repository has no meaning apart from the rule that uses it.

## 3. Criteria the Evaluator can settle

If two engineers could disagree about whether a criterion passed, it isn't one yet.

- **GIVEN** names concrete state. Use the seed ids — `task-001`–`004`, `user-001`–`005`,
  `project-001`/`002` — or state exactly what to create. Not "given some tasks exist".
- **WHEN** is a single trigger: method, path and body; or one service call; or one published event.
- **THEN** names something observable. A status code plus a body field. A persisted value. An error
  `code`, not "an error". An event type plus the payload field that carries the decision. Spell enum
  values exactly as [app-context](../app-context/SKILL.md) §4 has them.

Weak: *"invalid tasks are handled gracefully."*
Testable: *"THEN the response is 207 with `results[]` holding one entry per requested id, each
carrying `status` and either `updated: true` or an error `code` of `TASK_TRANSITION_NOT_ALLOWED`."*

## 4. Questions to answer before you write the contract

The Generator will invent an answer if you leave one open, and the Evaluator will fail it for
guessing:

- Partial failure: does one bad id fail the batch, or does the rest proceed?
- Error mapping: which `AppError` subtype for each rejection, and what `code` string?
- Events: one per changed item, or one for the batch? Which payload fields does the subscriber need?
- Idempotency: what does the same call twice do?
- Paths: the module is `activities` but the existing route base is `/api/tasks`. Decide, and say so in
  the contract.
- Vocabulary: if the feature needs a new enum value, add a criterion for it. Never let it arrive as a
  side effect.

## 5. Guardrails, per sprint

Name only the boundary that sprint could plausibly break, with the reason. "Must not inject
`NotificationService` into `TaskService`; the alert comes from an event" is worth writing. Reciting
all of [architecture-principles](../architecture-principles/SKILL.md) is not — the Generator reads it
anyway, and a wall of rules hides the one that matters here.
