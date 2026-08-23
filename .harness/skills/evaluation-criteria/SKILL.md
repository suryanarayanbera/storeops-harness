# Skill: Evaluation Criteria

**Goal:** This is how we score sprints. The rules are binary: you either pass or fail.

## 1. How We Grade
Every sprint is scored out of 100, but one failed "hard gate" means an automatic **FAIL** for the whole sprint.
* **A. Contract Fulfillment (40%):** Does the code do what the sprint asked? (Automated gate: `./mvnw clean test` must pass).
* **B. Architectural Compliance (35%):** Are the layer rules respected? (Automated gate: `ModuleBoundaryTest` and `Checkstyle`).
* **C. Test Quality (25%):** Do the tests actually prove anything? (Automated gate: `jacoco:check` for test coverage).

## 2. Dimension A: Contract Fulfillment
* **Hard Gate:** Every single Acceptance Criteria (AC) from the sprint contract must have a test proving it works. 
* **Hard Gate:** If a criterion is ignored, the sprint fails, even if the Generator admits to skipping it in the summary.

## 3. Dimension B: Architecture
`ModuleBoundaryTest` automatically checks for cross-module database joins, circular dependencies, and layer violations. 

**Manual Hard Gates:**
* **Missing Events:** If the contract says to publish an event and the code doesn't, FAIL.
* **Silent Failures:** If an event is published without `@Transactional`, or if a listener is missing `@Transactional(REQUIRES_NEW)`, FAIL.
* **Logic in Routes:** If there is business logic (like doing math or checking enums) inside a Controller, FAIL.

## 4. Dimension C: Test Quality
We don't accept tests that only check for a `200 OK` status without verifying the data.

**Manual Hard Gates:**
* **Negative Tests:** Every new service method must have a test for what happens when it fails (e.g., bad payload, wrong ID).
* **Verify Everything:** Tests must assert the database state, the published event, and the specific error code.
* **Event Delivery:** New events must include an `EventDeliveryIntegrationTest` to prove the subscriber received it.

## 5. The Final Verdict
1. Did any automated gate fail? **FAIL.**
2. Did any manual hard gate fail? **FAIL.**
3. If you can't tell if a gate passed or failed, don't guess. **FAIL.**
4. If everything passes but there are tiny issues (like a dead private method), issue a **CONDITIONAL PASS** and list the cleanups.
5. Otherwise, **PASS.**