package com.cognizant.storeops.staff.domain;

import java.time.Instant;

/**
 * Bearer token issued to a staff member.
 *
 * <p>Stub only: no authentication filter is wired into the scaffold. The type exists so that the
 * staff module owns the concept and later work has a place to put it.
 *
 * @param token     opaque token value
 * @param userId    staff member the token was issued to
 * @param issuedAt  issue time
 * @param expiresAt expiry time
 */
public record AuthToken(String token, String userId, Instant issuedAt, Instant expiresAt) {

    public boolean isExpiredAt(final Instant moment) {
        return expiresAt != null && moment.isAfter(expiresAt);
    }
}
