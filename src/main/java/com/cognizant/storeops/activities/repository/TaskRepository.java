package com.cognizant.storeops.activities.repository;

import com.cognizant.storeops.activities.domain.Task;
import com.cognizant.storeops.activities.domain.TaskCategory;
import com.cognizant.storeops.activities.domain.TaskPriority;
import com.cognizant.storeops.activities.domain.TaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Data access for operational activities. Owned by the activities module.
 *
 * <p>No other module may import this interface - cross-module reads go through
 * {@code TaskService}. Enforced by {@code ModuleBoundaryTest}.
 */
public interface TaskRepository {

    Task save(Task task);

    Optional<Task> findById(String id);

    boolean existsById(String id);

    List<Task> findAll();

    /** Any null criterion is ignored. */
    List<Task> search(TaskStatus status, TaskPriority priority, TaskCategory category, String storeId);

    List<Task> findByProjectId(String projectId);

    List<Task> findByStoreId(String storeId);

    /**
     * Activities whose deadline has passed and whose work is unfinished: {@code dueAt} before
     * {@code moment} and status other than {@code DONE}. An activity with no {@code dueAt} has no
     * deadline to miss and never matches.
     *
     * <p>Data predicate only. Which priority bands deserve an alert is a business rule and stays in
     * {@code Task.isSlaTracked()}, so this finder deliberately returns every band.
     */
    List<Task> findOpenPastDue(Instant moment);

    boolean deleteById(String id);
}
