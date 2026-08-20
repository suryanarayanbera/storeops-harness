package com.cognizant.storeops.activities.repository;

import com.cognizant.storeops.activities.domain.Task;
import com.cognizant.storeops.activities.domain.TaskCategory;
import com.cognizant.storeops.activities.domain.TaskPriority;
import com.cognizant.storeops.activities.domain.TaskStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Persistence mapping for the {@code tasks} table.
 *
 * <p>Deliberately separate from {@link Task}: the domain type stays an immutable record with no
 * knowledge of JPA, and this class absorbs the mutability and no-arg constructor that Hibernate
 * requires. The conversion lives here, so nothing above the repository layer changes when the
 * storage technology does.
 */
@Entity
@Table(name = "tasks")
public class TaskEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "description", length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TaskStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 20)
    private TaskPriority priority;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    private TaskCategory category;

    @Column(name = "store_id", nullable = false, length = 64)
    private String storeId;

    @Column(name = "project_id", length = 64)
    private String projectId;

    @Column(name = "assignee_id", length = 64)
    private String assigneeId;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. Not for application use. */
    protected TaskEntity() {
        // Hibernate instantiates through reflection.
    }

    static TaskEntity fromDomain(final Task task) {
        final TaskEntity entity = new TaskEntity();
        entity.id = task.id();
        entity.title = task.title();
        entity.description = task.description();
        entity.status = task.status();
        entity.priority = task.priority();
        entity.category = task.category();
        entity.storeId = task.storeId();
        entity.projectId = task.projectId();
        entity.assigneeId = task.assigneeId();
        entity.dueAt = task.dueAt();
        entity.createdAt = task.createdAt();
        entity.updatedAt = task.updatedAt();
        return entity;
    }

    Task toDomain() {
        return new Task(id, title, description, status, priority, category,
                storeId, projectId, assigneeId, dueAt, createdAt, updatedAt);
    }
}
