package com.cognizant.storeops.shared.error;

/** Caller is unauthenticated or presented an invalid {@code AuthToken}. Maps to HTTP 401. */
public class UnauthorizedError extends AppError {

    private static final long serialVersionUID = 1L;

    public UnauthorizedError(final String message) {
        super("UNAUTHORIZED", message, 401);
    }
}
