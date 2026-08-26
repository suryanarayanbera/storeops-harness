package com.cognizant.storeops.programmes.domain;

/**
 * One line of a task template: what to raise, which department owns it, and how urgent it is by
 * default.
 *
 * <p>{@code department} is matched against {@code User.profile().department()}, the only record of
 * departments in StoreOps - there is no {@code Department} entity and no enum, so it is a plain
 * {@code String} here too. {@code priority} is a {@code TaskPriority} name rather than the enum:
 * importing {@code activities.domain} into this module would put an edge alongside the one the
 * template event already creates in the other direction, and two edges between the same pair of
 * modules is the cycle {@code ModuleBoundaryTest} rule 2 exists to catch.
 *
 * @param title       activity title, used verbatim and also as the skip-if-present key downstream
 * @param description optional detail
 * @param department  department that owns this line, e.g. {@code GROCERY}
 * @param priority    {@code TaskPriority} name, e.g. {@code HIGH}
 */
public record PlanogramTemplateItem(
        String title,
        String description,
        String department,
        String priority) {
}
