package com.cognizant.storeops.shared.error;

/** Caller is authenticated but their {@code StaffRole} does not permit the operation. Maps to HTTP 403. */
public class ForbiddenError extends AppError {

    private static final long serialVersionUID = 1L;

    public ForbiddenError(final String message) {
        super("FORBIDDEN", message, 403);
    }
}
