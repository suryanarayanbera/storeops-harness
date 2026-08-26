package com.cognizant.storeops.shared.events;

/**
 * One fully-resolved activity to create, carried on {@link ProgrammeTemplateRequestedEvent}.
 *
 * <p>Every field is a {@code String}, including {@code category} and {@code priority}. Typing them
 * on {@code TaskCategory} and {@code TaskPriority} would drag {@code activities.domain} into
 * {@code shared}, which {@code ModuleBoundaryTest} rule 3b forbids for exactly this reason: an event
 * carrying a module's types makes every subscriber depend on that module. The subscriber parses them
 * back, and treats anything it does not recognise as a default rather than a failure.
 *
 * <p>"Resolved" is the important word. The publisher has already expanded the template and chosen
 * the assignee, so the subscriber needs no access to the template catalogue or to programme
 * membership. That is what keeps the {@code programmes} and {@code activities} modules free of any
 * import of each other, in either direction.
 *
 * @param title       activity title, unique within the template
 * @param description optional detail
 * @param category    {@code TaskCategory} name, always {@code PLANOGRAM} for the current catalogue
 * @param priority    {@code TaskPriority} name, the template's default for this line
 * @param assigneeId  staff member the department resolved to, or null when no member covers it
 */
public record TemplateTaskDefinition(
        String title,
        String description,
        String category,
        String priority,
        String assigneeId) {
}
