package com.cognizant.storeops.shared.events;

import java.time.Instant;

/**
 * Raised by the programmes module when a store programme is closed.
 *
 * <p>The reports module reacts by queueing a {@code STORE_SUMMARY} report. Programmes does not
 * import the reports module to make that happen.
 *
 * @param projectId    programme that closed
 * @param storeId      store the programme ran in
 * @param closedByUserId staff member who closed it
 * @param occurredAt   when it closed
 */
public record ProgrammeClosedEvent(
        String projectId,
        String storeId,
        String closedByUserId,
        Instant occurredAt) implements DomainEvent {

    @Override
    public String eventType() {
        return "PROGRAMME_CLOSED";
    }
}
