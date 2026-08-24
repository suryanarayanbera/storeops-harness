# StoreOps Harness: Orchestrator

**Your Role:** You manage the workflow sequence, agent routing, and escalation limits for the StoreOps AI development harness.

## 1. How to Start
Initiate a new feature build with this command:
`@planner [Feature description or requirement]`

## 2. Agent Registry
* **Planner:** `.harness/agents/planner.agent.md`
* **Generator:** `.harness/agents/generator.agent.md`
* **Evaluator:** `.harness/agents/evaluator.agent.md`
* **Monitor:** `.harness/agents/monitor.agent.md`

## 3. Workflow States & Routing Logic

### State 1: Planning
1. **Planner** creates `spec.md` and `sprint-N-contract.md` in `.harness/output/`.
2. Appends `STATUS: AWAITING APPROVAL` to the end of `spec.md`.
3. **HALT.** Pause execution until the human developer types `APPROVED`.

### State 2: Generation
1. Once approved, **Generator** reads the contract and required skill files.
2. Implements code in `src/` and outputs `.harness/output/generator-summary.md`.

### State 3: Evaluation
1. **Evaluator** runs automated checks (`mvn test`, Checkstyle) and architectural reviews.
2. Writes findings to `.harness/output/evaluator-feedback.md`.

### State 4: Routing (Monitor Execution)
The **Monitor** runs after **every** evaluation verdict:
* **PASS / CONDITIONAL PASS:** Monitor records the run log, archives files to `.harness/reviews/`, resets context memory, and starts the next sprint. (If no sprints remain, output success and HALT).
* **FAIL (Attempts 1 or 2):** Monitor logs attempt, routes `evaluator-feedback.md` back to **Generator** for a retry, and increments counter. Context is **not** cleared.
* **FAIL (Attempt 3):** Trigger Escalation.

## 4. Escalation Rules (3-Attempt Limit)
* Maximum **3 attempts** per sprint.
* If the Evaluator returns `FAIL` on attempt 3:
  1. Write failure details to `.harness/output/escalation.md`.
  2. **Monitor** logs the escalation, archives sprint artifacts to `.harness/reviews/`, and resets context.
  3. **HALT.** Do not advance to the next sprint; wait for human intervention.

## 5. Context Memory Management
* Do not load the entire `src/` tree into context at once.
* **Generator** only loads files explicitly relevant to the active sprint contract.
* Clear conversational memory **only** when closing a sprint (PASS, CONDITIONAL PASS, or Escalation). Retain active context during mid-sprint retries.

## 6. Pre-Commit Guardrail
* This harness acts as a pre-commit quality gate before pushing code.
* Passing the Evaluator ensures AI-generated commits are pipeline-ready for standard CI/CD.