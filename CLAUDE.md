# StoreOps AI Development Harness: Orchestrator

This file defines the execution sequence, routing logic, and governance constraints for Claude Code when modifying the StoreOps API.

## 1. Entry Instruction
To initiate a development cycle, use the following prompt format:
`@planner [Feature description or requirement]`

## 2. Agent Registry
The harness relies on specialized agents with distinct responsibilities and bounded contexts:
* **Planner**: `.harness/agents/planner.agent.md`
* **Generator**: `.harness/agents/generator.agent.md`
* **Evaluator**: `.harness/agents/evaluator.agent.md`
* **Monitor**: `.harness/agents/monitor.agent.md`

## 3. Execution Sequence & Routing Logic
Claude Code must strictly adhere to the following state machine:

### State 1: Planning
1. The **Planner** agent reads the user prompt.
2. It decomposes the request and writes `spec.md` and `sprint-N-contract.md` to `.harness/output/`.
3. It appends the exact marker `STATUS: AWAITING APPROVAL` to the end of `spec.md`.
4. **HALT EXECUTION.** Wait for the human developer to type `APPROVED`.

### State 2: Generation
1. Upon `APPROVED`, the **Generator** agent reads the current sprint contract and required skill files.
2. It implements the code in `src/` and writes `generator-summary.md` to `.harness/output/`.

### State 3: Evaluation
1. The **Evaluator** agent runs automated checks (e.g., `mvn test`, `checkstyle`) and reviews the Generator's output against architectural rules.
2. It writes a structured verdict to `.harness/output/evaluator-feedback.md`.

### State 4: Routing & Feedback Loop
Read the verdict in `evaluator-feedback.md`:
* **If PASS:** The **Monitor** agent archives the sprint artifacts to `.harness/reviews/`. Advance to the next sprint. If no sprints remain, output a success message and HALT.
* **If FAIL:** Pass `evaluator-feedback.md` back to the **Generator** for a retry. Increment the iteration counter.

## 4. Escalation Limits
* **Maximum Iterations:** A single sprint loop (Generator -> Evaluator) may only run a maximum of **3 times**.
* **Escalation Trigger:** If the Evaluator returns FAIL on the 3rd iteration, **HALT EXECUTION**.
* **Escalation Output:** Write a failure notice to `.harness/output/escalation.md` detailing the sprint name, the iteration count, and the specific blocking issue. 

## 5. Context Scoping Strategy
To prevent context window degradation and LLM hallucination over long runs:
* Do not load the entire `src/` tree into context at once.
* The Generator should only read files explicitly relevant to the current sprint contract.
* Clear conversational context memory after a sprint successfully reaches a PASS verdict and is archived by the Monitor.

## 6. CI/CD Relationship
* This harness is a **pre-commit governance layer**. 
* The Evaluator's automated checks (`mvn test`, Checkstyle) mirror the CI pipeline. Code that passes the Evaluator is expected to pass CI seamlessly. This harness does not replace standard GitHub/GitLab CI/CD gates; it acts as a feedforward mechanism to ensure AI-generated commits are pipeline-ready before they are pushed.