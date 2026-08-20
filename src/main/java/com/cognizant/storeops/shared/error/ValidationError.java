package com.cognizant.storeops.shared.error;

import java.util.List;

/** Request payload or argument failed validation. Maps to HTTP 400. */
public class ValidationError extends AppError {

    private static final long serialVersionUID = 1L;

    /** Transient because {@code List} is not itself serializable; see {@link #getDetails()}. */
    private final transient List<String> details;

    public ValidationError(final String message) {
        this(message, List.of());
    }

    public ValidationError(final String message, final List<String> details) {
        super("VALIDATION_FAILED", message, 400);
        this.details = List.copyOf(details);
    }

    /** Field-level messages, empty when the failure is not field-specific or after deserialization. */
    public List<String> getDetails() {
        return details == null ? List.of() : details;
    }
}
