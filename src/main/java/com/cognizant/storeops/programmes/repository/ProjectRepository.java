package com.cognizant.storeops.programmes.repository;

import com.cognizant.storeops.programmes.domain.Project;
import com.cognizant.storeops.programmes.domain.ProjectStatus;
import java.util.List;
import java.util.Optional;

/**
 * Data access for store programmes. Owned by the programmes module.
 *
 * <p>No other module may import this interface - cross-module reads go through
 * {@code ProjectService}. Enforced by {@code ModuleBoundaryTest}.
 */
public interface ProjectRepository {

    Project save(Project project);

    Optional<Project> findById(String id);

    boolean existsById(String id);

    List<Project> findAll();

    List<Project> findByStatus(ProjectStatus status);

    List<Project> findByStoreId(String storeId);

    List<Project> findByRegionId(String regionId);
}
