package com.cognizant.storeops.shared.error;

/** Requested resource does not exist. Maps to HTTP 404. */
public class NotFoundError extends AppError {

    private static final long serialVersionUID = 1L;

    public NotFoundError(final String code, final String message) {
        super(code, message, 404);
    }

    /** Convenience factory producing a {@code <RESOURCE>_NOT_FOUND} code. */
    public static NotFoundError of(final String resource, final String id) {
        return new NotFoundError(
                resource.toUpperCase(java.util.Locale.ROOT) + "_NOT_FOUND",
                resource + " '" + id + "' was not found");
    }
}
