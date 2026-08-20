package com.cognizant.storeops.programmes.repository;

import com.cognizant.storeops.programmes.domain.ProjectMember;
import com.cognizant.storeops.programmes.domain.ProjectRole;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.Instant;

/** Persistence mapping for one row of the {@code project_members} collection table. */
@Embeddable
public class ProjectMemberEmbeddable {

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private ProjectRole role;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    /** Required by JPA. Not for application use. */
    protected ProjectMemberEmbeddable() {
        // Hibernate instantiates through reflection.
    }

    static ProjectMemberEmbeddable fromDomain(final ProjectMember member) {
        final ProjectMemberEmbeddable embeddable = new ProjectMemberEmbeddable();
        embeddable.userId = member.userId();
        embeddable.role = member.role();
        embeddable.joinedAt = member.joinedAt();
        return embeddable;
    }

    ProjectMember toDomain() {
        return new ProjectMember(userId, role, joinedAt);
    }
}
