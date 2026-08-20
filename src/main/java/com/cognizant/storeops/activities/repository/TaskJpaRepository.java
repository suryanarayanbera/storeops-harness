package com.cognizant.storeops.activities.repository;

import com.cognizant.storeops.activities.domain.TaskCategory;
import com.cognizant.storeops.activities.domain.TaskPriority;
import com.cognizant.storeops.activities.domain.TaskStatus;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data access to the {@code tasks} table.
 *
 * <p>Package-private on purpose. {@code JpaTaskRepository} is the only thing that may use it; the
 * rest of the application depends on the {@link TaskRepository} interface, which says nothing about
 * JPA.
 */
interface TaskJpaRepository extends JpaRepository<TaskEntity, String> {

    /** Newest first, id as tie-breaker, so list responses are stable across calls. */
    Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"));

    @Query("""
            SELECT t FROM TaskEntity t
            WHERE (:status IS NULL OR t.status = :status)
              AND (:priority IS NULL OR t.priority = :priority)
              AND (:category IS NULL OR t.category = :category)
              AND (:storeId IS NULL OR t.storeId = :storeId)
            """)
    List<TaskEntity> search(
            @Param("status") TaskStatus status,
            @Param("priority") TaskPriority priority,
            @Param("category") TaskCategory category,
            @Param("storeId") String storeId,
            Sort sort);

    List<TaskEntity> findByProjectId(String projectId, Sort sort);

    List<TaskEntity> findByStoreId(String storeId, Sort sort);
}
