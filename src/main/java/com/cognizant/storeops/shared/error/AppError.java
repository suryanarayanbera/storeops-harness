package com.cognizant.storeops.shared.error;

/**
 * Base of the StoreOps typed error hierarchy.
 *
 * <p>Every error surfaced by a route or a service must be an {@code AppError} subtype. Raw
 * {@code RuntimeException} / {@code Error} throws are forbidden in {@code routes} and
 * {@code service} packages and are rejected by both Checkstyle and the ArchUnit boundary tests.
 *
 * <p>The triple {@code (code, message, statusCode)} is the wire contract: {@code code} is the
 * stable machine-readable identifier clients switch on, {@code message} is human-readable, and
 * {@code statusCode} is the HTTP status {@code GlobalExceptionHandler} will respond with.
 */
public abstract class AppError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final int statusCode;

    protected AppError(final String code, final String message, final int statusCode) {
        super(message);
        this.code = code;
        this.statusCode = statusCode;
    }

    protected AppError(final String code, final String message, final int statusCode, final Throwable cause) {
        super(message, cause);
        this.code = code;
        this.statusCode = statusCode;
    }

    public String getCode() {
        return code;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
