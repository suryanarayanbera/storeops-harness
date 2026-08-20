package com.cognizant.storeops.staff.domain;

import java.time.Instant;

/**
 * A store staff member. Immutable; updates produce a new instance.
 *
 * @param id          stable identifier
 * @param email       login identity
 * @param displayName name shown in the UI
 * @param role        position in the retail hierarchy
 * @param storeId     home store
 * @param regionId    region the home store belongs to
 * @param active      false once the staff member leaves
 * @param profile     contact and shift detail
 * @param createdAt   registration time
 */
public record User(
        String id,
        String email,
        String displayName,
        StaffRole role,
        String storeId,
        String regionId,
        boolean active,
        UserProfile profile,
        Instant createdAt) {

    public User {
        profile = profile == null ? UserProfile.empty() : profile;
    }
}
