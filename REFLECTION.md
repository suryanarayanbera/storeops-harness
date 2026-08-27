# Reflection: StoreOps Harness Implementation

Ten sprints across four core features (shift handover bulk update, SLA breach alerting, regional rollup report, planogram task template). Every sprint reached a **CONDITIONAL PASS on attempt 1 of 3**, with scores between 92–99. Full artefact records are available in [.harness/reviews/](.harness/reviews/).

## Environment & Implementation Wins

* **Setup & Environment Adaptations:** Since Docker wasn't available on my Cognizant laptop, I ran the harness using GitHub Remote Machines (GitHub Actions/Codespaces). For local execution, I used the locally installed Maven tooling instead of the Maven wrapper. 
* **Runtime & Stack Adjustments:** Because the environment was running Java 25 (with Java 21 unavailable), I updated `pom.xml` to target Java 25 directly. 
* **Database & Persistence Realities:** I replaced the initial hardcoded in-memory data structures with an H2 database to reflect actual persistence. 
* **Refactoring EventBus to Spring Application Events:** The initial `eventBus.publish` scaffold lacked a full implementation and relied on a custom publisher/consumer mechanism. I modified the implementation to use Spring's native `@EventListener` framework, aligning event publication cleanly with Spring's core context and lifecycle.
* **Fixing the Transactional Event Bug:** Adding JPA brought up a critical correctness issue: `TaskService.update()` originally published events inline *before* the transaction committed. If the commit failed downstream, an `ESCALATION` alert was already published for a state change that never actually hit the database. Fixing this required switching to `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` so events only fire after a successful DB commit.
* **Catching Hard Gates:** The four major failure modes from the spec never hit the main branch. Automated gates—like ArchUnit rules, Checkstyle error checks, and JaCoCo coverage thresholds—acted as strict binary pass/fail checks before the Evaluator even touched the code.

## Gaps Identified & Lessons Learned

* **Missing Endpoints & Unimplemented Features:** The scaffold was missing 9 REST endpoints outlined in the specification doc. Additionally, `eventBus.publish` lacked a proper underlying implementation out-of-the-box, requiring manual setup during the bootstrapping phase.
* **Sprint Iteration Logic Bug:** When running the harness across multiple features sequentially, it kept incrementing and creating the next sprint globally rather than starting fresh at Sprint 1 for each distinct feature. Features now archive cleanly into their own feature-specific folders.
* **Unused Escalation Paths:** Because every sprint passed the automated build gates on attempt 1, 0 out of 30 retries were ever used. `.harness/output/escalation.md` was never created, leaving the escalation path largely unverified in practice.
* **CONDITIONAL PASS Has No Loop Back:** `CLAUDE.md` currently treats `PASS` and `CONDITIONAL PASS` identical—both move straight to the next sprint. Minor findings (like minor refactoring hints or manual JSON parsing in tests) get logged to `run-log.md` but never automatically fixed.

## One Concrete Improvement

**Enforce an Open-Cleanups Backlog.** 
Instead of dropping `CONDITIONAL PASS` items into archived logs, the orchestrator should append any unresolved findings into the next sprint contract as mandatory prerequisites before starting new feature work.

* **Trade-off:** This consumes extra tokens and slightly delays starting new features to pay off technical debt. However, it prevents minor code quality issues from silently accumulating across multiple harness runs.