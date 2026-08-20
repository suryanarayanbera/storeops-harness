package com.cognizant.storeops.shared.error;

/** Request conflicts with current resource state, e.g. an illegal status transition. Maps to HTTP 409. */
public class ConflictError extends AppError {

    private static final long serialVersionUID = 1L;

    public ConflictError(final String code, final String message) {
        super(code, message, 409);
    }
}
