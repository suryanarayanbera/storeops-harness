# StoreOps Harness Design Brief

## A. Intent Decomposition
1. I split the work into clear boundaries so the agents don't overlap.
2. The Planner handles defining the work by creating the `spec.md` and explicit sprint contracts.
3. The Generator focuses entirely on writing the implementation code.
4. All sprint contracts use testable GIVEN/WHEN/THEN acceptance criteria to eliminate subjective LLM grading.
5. For example: GIVEN task `T-1` is IN_PROGRESS, WHEN the status updates to DONE, THEN assert a `TaskStatusChangedEvent` is published.
6. This setup forces the Generator to build complete vertical slices (touching the route, service, and repository at once) so the final code is immediately verifiable.

## B. Governance Framework
1. I created specific skill files to enforce the StoreOps architecture rules.
2. The `app-context` file is shared across all agents so they never hallucinate conflicting domain boundaries.
3. The `coding-conventions` file strictly prohibits raw `Error` throws, mandating the `AppError` typed hierarchy for safe API contracts.
4. Every evaluator verdict (`evaluator-feedback.md`) and generator summary is archived into the `.harness/reviews/` folder.
5. Committing this folder creates a permanent, searchable git audit trail, making it easy to debug if the AI's code quality drifts over multiple sprints.

## C. Non-Determinism Strategy
1. Because AI output is inherently variable, the Evaluator relies on binary, deterministic hard gates rather than subjective scoring to decide a PASS or FAIL verdict.
2. Any automated tool failure—such as a broken Maven build or a failed test—triggers an immediate FAIL verdict.
3. LLM-assessed checks, like verifying event bus integration, are strictly binary to prevent the Evaluator from passing bad code out of leniency.
4. If the Generator fails to pass the gates after a maximum of three iterations, the harness halts automatically and outputs an escalation notice to prevent endless loops.

## D. Architectural Decisions
1. **Decision:** I used automated tests as the primary hard gates instead of relying solely on the Evaluator to read the code.
2. **Rationale:** A local build command is deterministic, faster, and cheaper than asking Claude to manually inspect imports or logic layers.
3. **Decision:** A high evaluation score cannot override a hard gate failure.
4. **Rationale:** Even if the tests have perfect coverage, a cross-module repository import completely breaks the microservice boundary. It must fail immediately to prevent technical debt.