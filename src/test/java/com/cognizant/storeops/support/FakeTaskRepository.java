package com.cognizant.storeops.support;

import com.cognizant.storeops.activities.domain.Task;
import com.cognizant.storeops.activities.domain.TaskCategory;
import com.cognizant.storeops.activities.domain.TaskPriority;
import com.cognizant.storeops.activities.domain.TaskStatus;
import com.cognizant.storeops.activities.repository.TaskRepository;
import java.util.List;
import java.util.Objects;

/** Test double for {@link TaskRepository}. Starts empty; tests build the state they need. */
public class FakeTaskRepository extends FakeRepository<Task, String> implements TaskRepository {

    public FakeTaskRepository() {
        super(Task::id);
    }

    @Override
    public List<Task> search(
            final TaskStatus status,
            final TaskPriority priority,
            final TaskCategory category,
            final String storeId) {
        return findMatching(task ->
                (status == null || task.status() == status)
                        && (priority == null || task.priority() == priority)
                        && (category == null || task.category() == category)
                        && (storeId == null || Objects.equals(task.storeId(), storeId)));
    }

    @Override
    public List<Task> findByProjectId(final String projectId) {
        return findMatching(task -> Objects.equals(task.projectId(), projectId));
    }

    @Override
    public List<Task> findByStoreId(final String storeId) {
        return findMatching(task -> Objects.equals(task.storeId(), storeId));
    }
}
