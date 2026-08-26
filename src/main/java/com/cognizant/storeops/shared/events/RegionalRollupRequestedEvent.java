package com.cognizant.storeops.shared.events;

import java.time.Instant;

/**
 * Raised by the reports module when a regional rollup has been computed and answered.
 *
 * <p>The reports module also consumes this one, which is unusual for a domain event and deliberate.
 * It separates answering the request from recording that it happened: the rollup is a read, and the
 * {@code REGIONAL_ROLLUP} report record is a write that must not be able to fail the caller's
 * {@code GET}. Publishing means the recording happens after the read has committed, on its own
 * transaction, with the same isolation every other subscriber gets.
 *
 * <p>{@code storeCount} rather than the store ids: an event carrying the resolved set would grow
 * without bound and would tempt a subscriber into re-deriving figures the response already holds.
 * The count is enough to tell a reader of the report record how wide the rollup was.
 *
 * @param regionId    region that was rolled up
 * @param requestedBy staff member who asked, or {@code api} when the request named nobody
 * @param storeCount  how many stores the region resolved to
 * @param occurredAt  when the rollup was computed
 */
public record RegionalRollupRequestedEvent(
        String regionId,
        String requestedBy,
        int storeCount,
        Instant occurredAt) implements DomainEvent {

    @Override
    public String eventType() {
        return "REGIONAL_ROLLUP_REQUESTED";
    }
}
