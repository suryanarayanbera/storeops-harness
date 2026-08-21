# How to Test StoreOps

The contract's GIVEN/WHEN/THEN is the spec. This is where each test goes and what it has to assert.
Write them before the implementation, and get `./mvnw clean test` green before you write
`generator-summary.md`.

## 1. Pick the layer

| Testing | Style | Example |
| --- | --- | --- |
| Routes | `@WebMvcTest(XRoutes.class)`, service as `@MockitoBean` | `TaskRoutesTest` |
| Service rules | Plain JUnit. Fake repository, `RecordingEventBus`, fixed `Clock` | `TaskServiceTest` |
| Listener decisions | Construct the listener, call the handler | `AlertEventListenerTest` |
| Event delivery, JPA mapping | `@SpringBootTest` | `EventDeliveryIntegrationTest` |
| A new endpoint answers at all | One case in `ApiSmokeTest` | — |

One layer each. A route test that needs a repository means the route is doing too much.
`GlobalExceptionHandler` is a `@RestControllerAdvice`, so the slice gets the real error body for free.

## 2. Reuse the fixtures

`com.cognizant.storeops.support` has `FakeRepository<T, I>` (`save`, `findById`, `existsById`,
`findAll`, `deleteById`, `count`, protected `findMatching(Predicate)`, insertion order preserved), a
fake per module implementing that module's queries, `RecordingEventBus` with
`published(SomeEvent.class)`, and `FailingSubscriber` for error containment.

A new repository interface gets a new fake, not a Mockito mock. Mock only cross-module collaborators,
and only for reads — `mock(UserService.class)` in `TaskServiceTest` is right, because the staff
module's rules aren't under test there.

## 3. Freeze time

Services take a `Clock`. Pass `Clock.fixed(NOW, ZoneOffset.UTC)` off a constant
`Instant.parse("2026-02-01T10:00:00Z")` and assert timestamps exactly. No `Instant.now()` in an
assertion; the seed data is pinned to January 2026 for the same reason.

## 4. From criterion to assertion

Build the GIVEN with the fake's `save` or a small `seedTask(...)` helper. The WHEN is one call. The
THEN is the outcome, never the status code alone: the returned record's fields, what the repository
now holds, the events published and their payloads, and an error's `code`, `statusCode` and
`details` rather than just its type.

```java
assertThatExceptionOfType(ConflictError.class)
        .isThrownBy(() -> taskService.update("task-003", reopen()))
        .satisfies(error -> {
            assertThat(error.getCode()).isEqualTo("TASK_TRANSITION_NOT_ALLOWED");
            assertThat(error.getStatusCode()).isEqualTo(409);
        });
assertThat(statusEvents()).isEmpty();
```

Every AC gets a test asserting its THEN. Every new service method gets a negative case: the rejected
payload, the unknown id, the forbidden transition.

JaCoCo runs in the same command and holds services and listeners to 70% line and 50% branch each,
with the project at 85%/60%. A new service method with an untested branch drops its class below the
floor and fails the build, so the negative case isn't optional.

## 5. Events, in two halves

What got published belongs in the service test with `RecordingEventBus` — one event, the right
payload, plus the cases that must publish nothing (priority-only change, no-op status update,
rejected transition). Whether it arrives belongs in `EventDeliveryIntegrationTest`, which already
covers the quiet failures: rollback delivers nothing, publishing outside a transaction delivers
nothing, a throwing subscriber doesn't break the publisher. A new event or listener needs a case
there asserting the side effect happened — the recipient's notification count went up by one, with
the expected `alertType` and `sourceRef`.

Absence assertions need a partner. `assertThat(statusEvents()).isEmpty()` passes when publishing is
broken end to end, and `hasSize(before)` passes when dispatch never runs. Keep them, but only
alongside a positive test in the same class. Count before and after rather than assuming an empty
start; the seed data is already there.

## 6. Placement and style

Mirror the source package: `TaskRoutesTest`, `TaskServiceTest`, `AlertEventListenerTest`. Every test
gets a `@DisplayName` stating the rule in words, like "update refuses to reopen a DONE activity".
AssertJ everywhere, `jsonPath` in the slices.

Checkstyle lints test sources too, so `catch (Exception e)` fails the build here as well. Rename an
entity field and you update `data.sql` — `H2SchemaTest` is there to catch that drift.
