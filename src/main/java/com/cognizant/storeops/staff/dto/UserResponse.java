package com.cognizant.storeops.staff.dto;

import com.cognizant.storeops.staff.domain.User;
import java.time.Instant;

/**
 * Wire representation of a staff member. Deliberately narrower than the entity: no email is
 * exposed on the read endpoint.
 */
public record UserResponse(
        String id,
        String displayName,
        String role,
        String storeId,
        String regionId,
        boolean active,
        String department,
        String shiftPattern,
        Instant createdAt) {

    public static UserResponse from(final User user) {
        return new UserResponse(
                user.id(),
                user.displayName(),
                user.role() == null ? null : user.role().name(),
                user.storeId(),
                user.regionId(),
                user.active(),
                user.profile().department(),
                user.profile().shiftPattern(),
                user.createdAt());
    }
}
