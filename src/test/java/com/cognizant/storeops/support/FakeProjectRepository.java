package com.cognizant.storeops.support;

import com.cognizant.storeops.programmes.domain.Project;
import com.cognizant.storeops.programmes.domain.ProjectStatus;
import com.cognizant.storeops.programmes.repository.ProjectRepository;
import java.util.List;
import java.util.Objects;

/** Test double for {@link ProjectRepository}. Starts empty; tests build the state they need. */
public class FakeProjectRepository extends FakeRepository<Project, String> implements ProjectRepository {

    public FakeProjectRepository() {
        super(Project::id);
    }

    @Override
    public List<Project> findByStatus(final ProjectStatus status) {
        return findMatching(project -> project.status() == status);
    }

    @Override
    public List<Project> findByStoreId(final String storeId) {
        return findMatching(project -> Objects.equals(project.storeId(), storeId));
    }

    @Override
    public List<Project> findByRegionId(final String regionId) {
        return findMatching(project -> Objects.equals(project.regionId(), regionId));
    }
}
