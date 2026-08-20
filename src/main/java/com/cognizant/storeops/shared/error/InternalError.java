package com.cognizant.storeops.shared.error;

/**
 * Unexpected failure that still has to leave the application as a typed error.
 *
 * <p>Used by {@code GlobalExceptionHandler} to wrap non-{@code AppError} throwables so that every
 * HTTP error response has the same {@code (code, message, statusCode)} shape. Maps to HTTP 500.
 */
public class InternalError extends AppError {

    private static final long serialVersionUID = 1L;

    public InternalError(final String message) {
        super("INTERNAL_ERROR", message, 500);
    }

    public InternalError(final String message, final Throwable cause) {
        super("INTERNAL_ERROR", message, 500, cause);
    }
}
