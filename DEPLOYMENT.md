# Deployment

## 1. Target

**Docker, running on a GitHub Codespaces remote machine**, with port 8080 forwarded back to the
browser.

The deployment target in the capstone brief is "local Docker — containerise the application and run
via `docker compose up`". This is that, with one substitution: the container runs on a remote Linux
host instead of the development laptop.

**Why not the laptop.** Cognizant laptop doesn't have Docker installed.


| --- | --- |
| Repository | `github.com/suryanarayanbera/storeops-harness` |
| Host | GitHub Codespaces (Linux x64) |
| Runtime | Docker, via `docker compose up` |
| Image | `storeops-api:0.1.0-SNAPSHOT`, built from [Dockerfile](Dockerfile) |
| Base images | `eclipse-temurin:25-jdk` (build) → `eclipse-temurin:25-jre` (runtime) |
| Port | 8080, forwarded by the Codespace |
| Persistence | none — in-memory H2, re-seeded on every start |

## 2. What is in the image

[Dockerfile](Dockerfile) is two stages:

- **Build stage** runs the project's own Maven wrapper, so the image does not depend on a
  `maven:*-temurin-25` tag. It builds with `-DskipTests -Dcheckstyle.skip -Dspotbugs.skip
  -Djacoco.skip`. That is deliberate: the harness Evaluator already ran `./mvnw clean test` — all
  306 tests, 12 ArchUnit rules, Checkstyle, SpotBugs and the JaCoCo thresholds — as a hard gate
  before any sprint closed. Re-running the gates inside the image build doubles build time and
  proves nothing new.
- **Runtime stage** is JRE-only with no build tooling, runs as the non-root `storeops` user, and
  sizes the heap from the container's memory limit rather than the host's
  (`-XX:MaxRAMPercentage=75`).

## 3. Configuration and secrets

There are none, and that is a decision rather than an omission.

- **No secrets.** No database credentials, no API keys, no token store. The H2 datasource uses user
  `storeops` with a blank password against an in-memory URL that never leaves the process.
- **No volumes.** `spring.jpa.hibernate.ddl-auto=create-drop` plus `data.sql` means the schema and
  seed rows are rebuilt on every start. Restarting the container resets the data, which is why the
  curl examples in §5 give the same answer every time.

## 4. Steps taken

Run from the repository root inside the Codespace terminal.

```bash
# 1. Open the repository in a Codespace (GitHub → Code → Codespaces → Create codespace on main),
#    or from the laptop:
gh codespace create --repo suryanarayanbera/storeops-harness --branch main
gh codespace ssh

# 2. Build the image and start the container.
docker compose up --build -d

# 3. Confirm the container is up and the app has finished starting.
docker compose ps
docker compose logs --tail=20 storeops-api      # look for "Started StoreOpsApplication"

# 4. Confirm the API answers from inside the Codespace.
curl -s http://localhost:8080/api/tasks | head -c 400

# 5. Make port 8080 publicly reachable and print the forwarded URL.
gh codespace code -c $codespace_name
Then it will open editor
Then in the Ports panel (bottom panel, next to Terminal):
Forward a Port → 8080
Right-click the row → Port Visibility → Public
The Forwarded Address column is your URL — https://$codebase_name$-8080.app.github.dev

### 5.1 Shift handover bulk update — `PATCH /api/tasks/bulk-status`

The headline call: four activities in one request, three different outcomes, and a cross-module
event raised for the one that becomes `BLOCKED`.

```bash
curl -s -X PATCH "$BASE/api/tasks/bulk-status" \
  -H 'Content-Type: application/json' \
  -d '{"updates":[
        {"taskId":"task-001","status":"DONE"},
        {"taskId":"task-002","status":"BLOCKED"},
        {"taskId":"task-003","status":"BLOCKED"},
        {"taskId":"task-999","status":"DONE"}
      ]}'
```

Expected: `200`, with `task-001` and `task-002` under `succeeded`; `task-003` under `failed` as
`TASK_TRANSITION_NOT_ALLOWED` (409 — it is already `DONE`, which is terminal); and `task-999` under
`failed` as `TASK_NOT_FOUND` (404). One bad id does not roll back the others — that is the partial
failure handling the sprint contract specified.

```json
```

### 5.2 The event bus, in the running container — `GET /api/notifications`

`task-002` is assigned to `user-003`. Blocking it publishes `TaskStatusChangedEvent`, which the
`alerts` module picks up after commit and turns into an `ESCALATION` notification. The `activities`
module imports nothing from `alerts` to make that happen.

```bash
curl -s "$BASE/api/notifications?recipientId=user-003"
```

Expected: the seeded `SHIFT_HANDOVER` notification, plus a **new `ESCALATION`** notification naming
`task-002` that did not exist before the call in §5.1.

```json
```

### 5.3 Regional rollup report — `GET /api/reports/region/{regionId}`

```bash
curl -s "$BASE/api/reports/region/region-north?requestedBy=user-001"
```

Expected: `200`, aggregating both stores in `region-north` — completion rates, overdue counts by
`TaskCategory`, and the blocked activity list. A `REGIONAL_ROLLUP` report record is queued via the
event bus without the read being able to fail on it.

```json
```

### 5.4 Planogram task template — `POST /api/projects/{id}/templates`

```bash
curl -s -X POST "$BASE/api/projects/project-001/templates" \
  -H 'Content-Type: application/json' \
  -d '{"templateId":"PLANOGRAM_STANDARD","requestedBy":"user-002"}'

# The activities are created by the activities module after commit — read them back:
curl -s "$BASE/api/tasks?storeId=store-001&category=PLANOGRAM"
```

Expected: `202 Accepted` with four assignments (two `OPERATIONS`, two `GROCERY`, priorities
`HIGH`/`HIGH`/`MEDIUM`/`LOW`), then four new `PLANOGRAM` activities on `store-001` alongside the
seeded `task-002`.

```json
```



## 6. Teardown

```bash
docker compose down            # stop and remove the container
gh codespace stop -c "$CODESPACE_NAME"
gh codespace delete -c "$CODESPACE_NAME"
```

No volumes to prune and no cloud resources left behind — the whole deployment is one container and
one Codespace.

