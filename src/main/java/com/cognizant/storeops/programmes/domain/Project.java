package com.cognizant.storeops.programmes.domain;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

/**
 * A store programme: a seasonal rollout, compliance drive or store refit. Immutable.
 *
 * @param id          stable identifier
 * @param name        programme name
 * @param description optional detail
 * @param status      lifecycle state
 * @param storeId     store the programme runs in
 * @param regionId    region the store belongs to
 * @param ownerId     staff member accountable for the programme
 * @param members     programme membership
 * @param createdAt   creation time
 * @param closedAt    close time, null while open
 */
public record Project(
        String id,
        String name,
        String description,
        ProjectStatus status,
        String storeId,
        String regionId,
        String ownerId,
        List<ProjectMember> members,
        Instant createdAt,
        Instant closedAt) {

    public Project {
        members = members == null ? List.of() : List.copyOf(members);
    }

    public boolean isClosed() {
        return status == ProjectStatus.CLOSED;
    }

    public Project withStatus(final ProjectStatus newStatus, final Instant closedTime) {
        return new Project(id, name, description, newStatus, storeId, regionId, ownerId, members, createdAt, closedTime);
    }

    public Project withMember(final ProjectMember member) {
        final List<ProjectMember> next = Stream.concat(
                        members.stream().filter(existing -> !existing.userId().equals(member.userId())),
                        Stream.of(member))
                .toList();
        return new Project(id, name, description, status, storeId, regionId, ownerId, next, createdAt, closedAt);
    }
}
