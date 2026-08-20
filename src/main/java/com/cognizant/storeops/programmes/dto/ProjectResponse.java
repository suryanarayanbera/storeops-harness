package com.cognizant.storeops.programmes.dto;

import com.cognizant.storeops.programmes.domain.Project;
import java.time.Instant;
import java.util.List;

/** Wire representation of a store programme. */
public record ProjectResponse(
        String id,
        String name,
        String description,
        String status,
        String storeId,
        String regionId,
        String ownerId,
        List<ProjectMemberResponse> members,
        Instant createdAt,
        Instant closedAt) {

    public ProjectResponse {
        members = members == null ? List.of() : List.copyOf(members);
    }

    public static ProjectResponse from(final Project project) {
        return new ProjectResponse(
                project.id(),
                project.name(),
                project.description(),
                project.status() == null ? null : project.status().name(),
                project.storeId(),
                project.regionId(),
                project.ownerId(),
                project.members().stream().map(ProjectMemberResponse::from).toList(),
                project.createdAt(),
                project.closedAt());
    }
}
