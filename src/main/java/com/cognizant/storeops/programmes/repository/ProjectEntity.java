package com.cognizant.storeops.programmes.repository;

import com.cognizant.storeops.programmes.domain.Project;
import com.cognizant.storeops.programmes.domain.ProjectMember;
import com.cognizant.storeops.programmes.domain.ProjectStatus;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Persistence mapping for the {@code projects} table.
 *
 * <p>Programme membership becomes a {@code project_members} collection table keyed by
 * {@code project_id}, rather than the nested list the domain record carries.
 */
@Entity
@Table(name = "projects")
public class ProjectEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProjectStatus status;

    @Column(name = "store_id", nullable = false, length = 64)
    private String storeId;

    @Column(name = "region_id", length = 64)
    private String regionId;

    @Column(name = "owner_id", nullable = false, length = 64)
    private String ownerId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "project_members", joinColumns = @JoinColumn(name = "project_id"))
    private List<ProjectMemberEmbeddable> members = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    /** Required by JPA. Not for application use. */
    protected ProjectEntity() {
        // Hibernate instantiates through reflection.
    }

    static ProjectEntity fromDomain(final Project project) {
        final ProjectEntity entity = new ProjectEntity();
        entity.id = project.id();
        entity.name = project.name();
        entity.description = project.description();
        entity.status = project.status();
        entity.storeId = project.storeId();
        entity.regionId = project.regionId();
        entity.ownerId = project.ownerId();
        entity.members = project.members().stream()
                .map(ProjectMemberEmbeddable::fromDomain)
                .collect(Collectors.toCollection(ArrayList::new));
        entity.createdAt = project.createdAt();
        entity.closedAt = project.closedAt();
        return entity;
    }

    Project toDomain() {
        final List<ProjectMember> domainMembers = members.stream()
                .map(ProjectMemberEmbeddable::toDomain)
                .toList();
        return new Project(id, name, description, status, storeId, regionId, ownerId,
                domainMembers, createdAt, closedAt);
    }
}
