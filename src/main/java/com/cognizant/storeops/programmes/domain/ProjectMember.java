package com.cognizant.storeops.programmes.domain;

import java.time.Instant;

/**
 * A staff member's membership of a store programme.
 *
 * <p>Holds the staff id only. The programmes module resolves names through the staff module's
 * service when it needs them; it never stores a copy of staff data.
 *
 * @param userId   staff member
 * @param role     role held on this programme
 * @param joinedAt when they joined
 */
public record ProjectMember(String userId, ProjectRole role, Instant joinedAt) {
}
