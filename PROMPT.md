# Demonstration Run Prompt

The feature prompt used to invoke the harness for the demonstration run.

```text
@planner Add shift handover bulk update — PATCH /api/activities/bulk-status allowing outgoing shift staff to mark multiple operational activities as DONE or BLOCKED in a single request, with partial failure handling and an audit entry per updated task.



@planner Add SLA breach alerting — when a HIGH or CRITICAL task passes its due date without reaching DONE, automatically fire a SLA_BREACH notification to the assigned Department Lead and escalate to STORE_MANAGER if unresolved after a configurable grace period.


@planner Add regional rollup report — GET /api/reports/region/:id aggregating task completion rates, overdue counts by TaskCategory, and blocked task lists across all stores in the region; triggers a REGIONAL_ROLLUP Report record via the event bus.



@planner Add planogram task template — POST /api/programmes/:id/templates to clone a standard set of PLANOGRAM tasks into a new store programme, applying department assignments and default priorities from the template definition.

```