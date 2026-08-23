# Skill: How to Test

**Goal:** The GIVEN/WHEN/THEN criteria in the contract is your exact spec. All tests must pass (`./mvnw clean test`) before you wrap up a sprint.

## 1. Pick the Right Tool
* **Routes:** Use `@WebMvcTest` and mock the Service layer.
* **Services:** Use plain JUnit. Use our custom Fake Repositories, `RecordingEventBus`, and a fixed `Clock`.
* **Event Delivery & JPA:** Use `@SpringBootTest`.
* **Listeners:** Construct the listener directly and call the handler.

## 2. Use Fakes, Not Mocks
* Use `FakeRepository` (found in the `support` package) for database operations instead of mocking repositories. 
* Only use Mockito when you need to mock a read operation from a completely different module.
* **Time:** Always use a fixed Clock pinned to `2026-02-01T10:00:00Z` because our seed data relies on it. Never use `Instant.now()` in your assertions.

## 3. Assert Everything
* Don't just check for a 200 OK status. Your THEN blocks must verify the returned record, the final database state, the specific error codes, and any events fired.
* **Coverage is strict:** Services and listeners require 70% line coverage and 50% branch coverage. You *must* write negative test cases (like testing a rejected payload or a bad ID) or the build will fail.

## 4. Testing Events
* **Did it publish?** Check this in your Service tests using `RecordingEventBus` to ensure the exact event and payload were fired. Always test that *no* events are published when they shouldn't be.
* **Did it arrive?** Check this in `EventDeliveryIntegrationTest` by proving the subscriber actually processed the side effect.

## 5. Style Rules
* Write clear, plain-English test names using `@DisplayName` (e.g., "update refuses to reopen a DONE activity").
* Use AssertJ everywhere.
* Remember that test files are linted too, so bad practices like catching generic exceptions will fail the build.