package com.cognizant.storeops.shared.events;

import java.time.Instant;
import java.util.List;

/**
 * Raised by the programmes module when a task template has been applied to a programme.
 *
 * <p>The programmes module validates the programme, expands the template and resolves each line's
 * department to a member of that programme. What it cannot do is create the activities: those belong
 * to {@code activities}, and a direct write into another module's store is banned. So it publishes
 * the resolved lines and the {@code activities} module creates the rows.
 *
 * <p>That is also why the payload carries {@code items} rather than the {@code templateId} alone. A
 * subscriber handed only the id would have to read the catalogue and the programme's membership to
 * work out what to create, and the import that made that possible - {@code activities} reaching into
 * {@code programmes}, while {@code programmes} publishes to {@code activities} - is the dependency
 * cycle {@code ModuleBoundaryTest} rule 2 fails on. Resolving before publishing is what keeps both
 * modules ignorant of each other.
 *
 * <p>The {@code templateId} travels anyway, for the subscriber's log line and for anyone reading the
 * event stream later. Nothing is derived from it.
 *
 * @param projectId   programme the activities belong to
 * @param storeId     store the programme runs in, copied onto every created activity
 * @param templateId  template that was applied, for diagnostics
 * @param requestedBy staff member who asked, or {@code api} when the request named nobody
 * @param items       the resolved activities to create, in catalogue order
 * @param occurredAt  when the template was applied
 */
public record ProgrammeTemplateRequestedEvent(
        String projectId,
        String storeId,
        String templateId,
        String requestedBy,
        List<TemplateTaskDefinition> items,
        Instant occurredAt) implements DomainEvent {

    public ProgrammeTemplateRequestedEvent {
        items = items == null ? List.of() : List.copyOf(items);
    }

    @Override
    public String eventType() {
        return "PROGRAMME_TEMPLATE_REQUESTED";
    }
}
