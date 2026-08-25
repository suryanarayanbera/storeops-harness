package com.cognizant.storeops.activities.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.cognizant.storeops.activities.domain.Task;
import com.cognizant.storeops.activities.domain.TaskCategory;
import com.cognizant.storeops.activities.domain.TaskPriority;
import com.cognizant.storeops.activities.domain.TaskStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The overdue sweep's finder, against the real H2 schema.
 *
 * <p>Worth having as an integration test rather than a fake: the point of {@code findOpenPastDue} is
 * that the predicate runs in the database, so a fake proves nothing about it. A null {@code dueAt}
 * must not match, which relies on SQL's three-valued logic rather than on Java's null handling.
 *
 * <p>The fixture rows are created here with ids of their own rather than asserted against the seed
 * rows, because the H2 database is shared with every other {@code @SpringBootTest} in the suite and
 * {@code ApiSmokeTest} moves seed {@code task-002} to {@code DONE}. Only {@code task-001}, which no
 * integration test mutates, is asserted from the seed data.
 */
@SpringBootTest
class TaskRepositoryIntegrationTest {

    /** Later than every seed due date, so the seed breaches are visible whatever the wall clock says. */
    private static final Instant SWEEP_MOMENT = Instant.parse("2026-02-01T10:00:00Z");

    private static final Instant PAST = Instant.parse("2026-01-07T08:00:00Z");
    private static final Instant FUTURE = Instant.parse("2026-03-01T08:00:00Z");

    @Autowired
    private TaskRepository taskRepository;

    private Task store(final String id, final TaskStatus status, final TaskPriority priority, final Instant dueAt) {
        return taskRepository.save(new Task(id, "Finder fixture " + id, null, status, priority,
                TaskCategory.RESTOCKING, "store-finder", null, "user-004", dueAt, PAST, PAST));
    }

    @Test
    @DisplayName("findOpenPastDue returns past-due unfinished activities and nothing else")
    void findOpenPastDueAppliesThePredicateInTheDatabase() {
        store("finder-open-high", TaskStatus.TODO, TaskPriority.HIGH, PAST);
        store("finder-open-medium", TaskStatus.IN_PROGRESS, TaskPriority.MEDIUM, PAST);
        store("finder-done", TaskStatus.DONE, TaskPriority.CRITICAL, PAST);
        store("finder-no-due-date", TaskStatus.BLOCKED, TaskPriority.CRITICAL, null);
        store("finder-not-yet-due", TaskStatus.TODO, TaskPriority.HIGH, FUTURE);

        final List<Task> openPastDue = taskRepository.findOpenPastDue(SWEEP_MOMENT);

        // The MEDIUM row proves the query does not filter on priority: the HIGH/CRITICAL band is a
        // business rule and stays in Task.isSlaTracked(), not in SQL.
        assertThat(openPastDue).extracting(Task::id)
                .contains("task-001", "finder-open-high", "finder-open-medium")
                .doesNotContain("finder-done", "finder-no-due-date", "finder-not-yet-due");
        assertThat(openPastDue).allSatisfy(task -> {
            assertThat(task.status()).isNotEqualTo(TaskStatus.DONE);
            assertThat(task.dueAt()).isNotNull().isBefore(SWEEP_MOMENT);
        });
    }

    @Test
    @DisplayName("findOpenPastDue treats a null moment as matching nothing")
    void findOpenPastDueRejectsANullMoment() {
        assertThat(taskRepository.findOpenPastDue(null)).isEmpty();
    }
}
