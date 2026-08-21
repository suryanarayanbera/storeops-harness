# Demonstration Run Prompt

The feature prompt used to invoke the harness for the demonstration run.
Feature source: Case Study §3.4 — "Add shift handover bulk update".

```text
@planner Add shift handover bulk update to the activities module.

Add PATCH /api/tasks/bulk-status so outgoing shift staff can mark several
operational activities DONE or BLOCKED in one request.

Each task is updated independently: an unknown id or a forbidden status
transition must fail only that task, not the others. The response reports
per-task success and failure.

Every successful update raises TaskStatusChangedEvent, exactly as the
existing single-task update path does.
```

## Notes

- Route base is `/api/tasks`, not `/api/activities` — `TaskRoutes` already owns
  `@RequestMapping("/api/tasks")`, and one controller keeps one base path.
  See [app-context](.harness/skills/app-context/SKILL.md) §7 on the naming split.
- Single module (`activities`), so this is expected to decompose into one sprint.
