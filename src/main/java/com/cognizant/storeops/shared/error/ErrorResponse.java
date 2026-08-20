package com.cognizant.storeops.shared.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * The single error body shape returned by every StoreOps endpoint.
 *
 * @param code       stable machine-readable error identifier, e.g. {@code TASK_NOT_FOUND}
 * @param message    human-readable explanation
 * @param statusCode HTTP status, repeated in the body so clients logging only the payload keep it
 * @param path       request URI that produced the error
 * @param timestamp  when the error was produced
 * @param details    field-level validation messages; omitted from JSON when empty
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ErrorResponse(
        String code,
        String message,
        int statusCode,
        String path,
        Instant timestamp,
        List<String> details) {

    public ErrorResponse {
        details = details == null ? List.of() : List.copyOf(details);
    }

    public static ErrorResponse of(final AppError error, final String path, final Instant timestamp) {
        final List<String> details = error instanceof ValidationError validation ? validation.getDetails() : List.of();
        return new ErrorResponse(error.getCode(), error.getMessage(), error.getStatusCode(), path, timestamp, details);
    }
}
