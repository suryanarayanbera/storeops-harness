package com.cognizant.storeops.programmes.dto;

import com.cognizant.storeops.programmes.domain.ProjectMember;
import java.time.Instant;

/** Wire representation of one programme membership. */
public record ProjectMemberResponse(String userId, String role, Instant joinedAt) {

    public static ProjectMemberResponse from(final ProjectMember member) {
        return new ProjectMemberResponse(
                member.userId(),
                member.role() == null ? null : member.role().name(),
                member.joinedAt());
    }
}
