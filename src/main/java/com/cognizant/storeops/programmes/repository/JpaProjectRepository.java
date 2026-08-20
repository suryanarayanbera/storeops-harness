package com.cognizant.storeops.programmes.repository;

import com.cognizant.storeops.programmes.domain.Project;
import com.cognizant.storeops.programmes.domain.ProjectStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * H2-backed {@link ProjectRepository}. Maps between the immutable {@link Project} record and
 * {@link ProjectEntity}.
 */
@Repository
public class JpaProjectRepository implements ProjectRepository {

    private final ProjectJpaRepository projects;

    JpaProjectRepository(final ProjectJpaRepository projects) {
        this.projects = projects;
    }

    @Override
    public Project save(final Project project) {
        return projects.save(ProjectEntity.fromDomain(project)).toDomain();
    }

    @Override
    public Optional<Project> findById(final String id) {
        return id == null ? Optional.empty() : projects.findById(id).map(ProjectEntity::toDomain);
    }

    @Override
    public boolean existsById(final String id) {
        return id != null && projects.existsById(id);
    }

    @Override
    public List<Project> findAll() {
        return toDomain(projects.findAll(ProjectJpaRepository.DEFAULT_SORT));
    }

    @Override
    public List<Project> findByStatus(final ProjectStatus status) {
        return toDomain(projects.findByStatus(status, ProjectJpaRepository.DEFAULT_SORT));
    }

    @Override
    public List<Project> findByStoreId(final String storeId) {
        return toDomain(projects.findByStoreId(storeId, ProjectJpaRepository.DEFAULT_SORT));
    }

    @Override
    public List<Project> findByRegionId(final String regionId) {
        return toDomain(projects.findByRegionId(regionId, ProjectJpaRepository.DEFAULT_SORT));
    }

    private static List<Project> toDomain(final List<ProjectEntity> entities) {
        return entities.stream().map(ProjectEntity::toDomain).toList();
    }
}
