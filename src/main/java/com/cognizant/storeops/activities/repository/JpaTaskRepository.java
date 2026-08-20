package com.cognizant.storeops.activities.repository;

import com.cognizant.storeops.activities.domain.Task;
import com.cognizant.storeops.activities.domain.TaskCategory;
import com.cognizant.storeops.activities.domain.TaskPriority;
import com.cognizant.storeops.activities.domain.TaskStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * H2-backed {@link TaskRepository}.
 *
 * <p>Translates between the immutable {@link Task} record the rest of the module speaks and the
 * {@link TaskEntity} Hibernate persists. Repository layer only: no HTTP, no events, no other module.
 */
@Repository
public class JpaTaskRepository implements TaskRepository {

    private final TaskJpaRepository tasks;

    JpaTaskRepository(final TaskJpaRepository tasks) {
        this.tasks = tasks;
    }

    @Override
    public Task save(final Task task) {
        return tasks.save(TaskEntity.fromDomain(task)).toDomain();
    }

    @Override
    public Optional<Task> findById(final String id) {
        return id == null ? Optional.empty() : tasks.findById(id).map(TaskEntity::toDomain);
    }

    @Override
    public boolean existsById(final String id) {
        return id != null && tasks.existsById(id);
    }

    @Override
    public List<Task> findAll() {
        return toDomain(tasks.findAll(TaskJpaRepository.DEFAULT_SORT));
    }

    @Override
    public List<Task> search(
            final TaskStatus status,
            final TaskPriority priority,
            final TaskCategory category,
            final String storeId) {
        return toDomain(tasks.search(status, priority, category, storeId, TaskJpaRepository.DEFAULT_SORT));
    }

    @Override
    public List<Task> findByProjectId(final String projectId) {
        return toDomain(tasks.findByProjectId(projectId, TaskJpaRepository.DEFAULT_SORT));
    }

    @Override
    public List<Task> findByStoreId(final String storeId) {
        return toDomain(tasks.findByStoreId(storeId, TaskJpaRepository.DEFAULT_SORT));
    }

    @Override
    public boolean deleteById(final String id) {
        if (id == null || !tasks.existsById(id)) {
            return false;
        }
        tasks.deleteById(id);
        return true;
    }

    private static List<Task> toDomain(final List<TaskEntity> entities) {
        return entities.stream().map(TaskEntity::toDomain).toList();
    }
}
