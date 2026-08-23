# Skill: Sprint Decomposition

**Goal:** How to chop up big features into small, testable sprints.

## 1. Slice Vertically
Don't just build a database table in one sprint and the API in the next. 
* Build the whole flow for a single feature at once. 
* For example, a single sprint should handle the Controller, Service, and Repository just for "Create Task."

## 2. Respect Module Borders
Keep cross-module communication clean. 
* If Module A does something that affects Module B, Sprint 1 is just Module A firing off an event. 
* Building the listener in Module B is always Sprint 2.

## 3. Be Crystal Clear
The AI Generator needs specific GIVEN/WHEN/THEN criteria to write good JUnit tests.
* **Bad:** "When a task is done, save it."
* **Good:** "GIVEN task `123` is `IN_PROGRESS`, WHEN a PUT request marks it `DONE`, THEN return HTTP 200 AND verify the database reads `DONE`."

## 4. No Magic Allowed
Every single sprint has to be testable on its own.
* If your test needs data from a completely different module, don't try to join the databases. 
* Just instruct the Generator to mock that service or use fake seed data.