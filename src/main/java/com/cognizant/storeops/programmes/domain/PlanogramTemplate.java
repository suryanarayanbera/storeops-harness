package com.cognizant.storeops.programmes.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A named, ordered set of activities to clone into a programme.
 *
 * <p>The catalogue is code rather than a table. There is no endpoint to maintain it, it changes only
 * when this file changes, and holding it here means it is versioned with the resolution logic that
 * reads it instead of needing a migration to stay in step. If templates ever become
 * caller-editable that decision reverses, and the seam is {@link #findById(String)}.
 *
 * <p>It lives in {@code programmes} because expanding a template means resolving each line's
 * department to a member of the programme, which needs {@link ProjectMember} and the staff roster.
 * The {@code activities} module never sees this class - it receives lines that have already been
 * resolved.
 *
 * @param templateId stable id callers name in the request
 * @param items      the activities to raise, in the order they should be created
 */
public record PlanogramTemplate(String templateId, List<PlanogramTemplateItem> items) {

    /** The one template that ships: a standard planogram reset sweep. */
    public static final String PLANOGRAM_STANDARD = "PLANOGRAM_STANDARD";

    /** Every line in the catalogue is a planogram activity. */
    public static final String CATEGORY = "PLANOGRAM";

    private static final Map<String, PlanogramTemplate> CATALOGUE = catalogue(new PlanogramTemplate(
            PLANOGRAM_STANDARD,
            List.of(
                    new PlanogramTemplateItem(
                            "Reset entrance promotional bay",
                            "Strip and rebuild the entrance bay to the current promotional plan",
                            "OPERATIONS",
                            "HIGH"),
                    new PlanogramTemplateItem(
                            "Reset grocery aisle planograms",
                            "Work the grocery aisles bay by bay against the issued planograms",
                            "GROCERY",
                            "HIGH"),
                    new PlanogramTemplateItem(
                            "Verify shelf-edge labelling",
                            "Check every reset bay for correct and legible shelf-edge labels",
                            "GROCERY",
                            "MEDIUM"),
                    new PlanogramTemplateItem(
                            "Photograph completed bays for compliance",
                            "Photograph each completed bay and file against the programme",
                            "OPERATIONS",
                            "LOW"))));

    public PlanogramTemplate {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /**
     * Looks a template up by id.
     *
     * <p>Returns empty rather than throwing, so the caller decides the error. An unknown id is a
     * {@code ValidationError} raised by the service with the known ids listed, not a
     * {@code NotFoundError} - the template is not a resource the API exposes, it is a value the
     * request supplies, and getting it wrong is a bad request.
     *
     * @param templateId id to look for; null, blank or unknown all return empty
     */
    public static Optional<PlanogramTemplate> findById(final String templateId) {
        if (templateId == null || templateId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(CATALOGUE.get(templateId.trim()));
    }

    /** Every known template id, sorted, for the error detail on an unknown one. */
    public static List<String> knownIds() {
        return CATALOGUE.keySet().stream().sorted().toList();
    }

    private static Map<String, PlanogramTemplate> catalogue(final PlanogramTemplate... templates) {
        final Map<String, PlanogramTemplate> byId = new LinkedHashMap<>();
        for (final PlanogramTemplate template : templates) {
            byId.put(template.templateId(), template);
        }
        return Map.copyOf(byId);
    }
}
