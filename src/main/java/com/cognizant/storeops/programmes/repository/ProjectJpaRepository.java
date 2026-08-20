package com.cognizant.storeops.programmes.repository;

import com.cognizant.storeops.programmes.domain.ProjectStatus;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data access to the {@code projects} table. Package-private: only
 * {@code JpaProjectRepository} may use it.
 */
interface ProjectJpaRepository extends JpaRepository<ProjectEntity, String> {

    /** Newest first, id as tie-breaker, so list responses are stable across calls. */
    Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"));

    List<ProjectEntity> findByStatus(ProjectStatus status, Sort sort);

    List<ProjectEntity> findByStoreId(String storeId, Sort sort);

    List<ProjectEntity> findByRegionId(String regionId, Sort sort);
}
