package com.cognizant.storeops.shared.error;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Translates thrown errors into the single {@link ErrorResponse} wire shape.
 *
 * <p>This is the only place in the application permitted to know about HTTP status numbers other
 * than the {@code AppError} subtypes themselves. Framework exceptions raised before a route body
 * runs (bean validation, unparseable JSON, bad enum in a query parameter) are funnelled into the
 * same typed hierarchy so that clients never see a Spring default error body.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AppError.class)
    public ResponseEntity<ErrorResponse> handleAppError(final AppError error, final HttpServletRequest request) {
        if (error.getStatusCode() >= 500) {
            LOG.error("{} on {}: {}", error.getCode(), request.getRequestURI(), error.getMessage(), error);
        } else {
            LOG.debug("{} on {}: {}", error.getCode(), request.getRequestURI(), error.getMessage());
        }
        return respond(error, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleBeanValidation(
            final MethodArgumentNotValidException exception, final HttpServletRequest request) {
        final List<String> details = exception.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::describe)
                .sorted()
                .toList();
        return respond(new ValidationError("Request validation failed", details), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            final MethodArgumentTypeMismatchException exception, final HttpServletRequest request) {
        final String message = "Parameter '" + exception.getName() + "' has an unsupported value";
        return respond(new ValidationError(message), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(
            final HttpMessageNotReadableException exception, final HttpServletRequest request) {
        LOG.debug("Unreadable request body on {}", request.getRequestURI(), exception);
        return respond(new ValidationError("Request body is missing or malformed"), request);
    }

    /**
     * Last-resort handler, with one branch that is not a defect.
     *
     * <p>Spring signals its own HTTP-level failures - unmapped path, wrong method, unsupported media
     * type - with exceptions implementing {@code org.springframework.web.ErrorResponse} (not to be
     * confused with the {@link ErrorResponse} record this class returns). Those already carry the
     * right status, so it is kept and only the body shape is normalised; otherwise a missing route
     * would be reported as a 500.
     *
     * <p>Anything else reaching here <em>is</em> a defect: services and routes are required to raise
     * {@link AppError} subtypes, so an untyped throwable means a rule was broken upstream.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(final Exception exception, final HttpServletRequest request) {
        if (exception instanceof org.springframework.web.ErrorResponse framework) {
            return respondFramework(framework, request);
        }
        LOG.error("Untyped exception escaped to the error handler on {}", request.getRequestURI(), exception);
        return respond(new InternalError("An unexpected error occurred"), request);
    }

    private static ResponseEntity<ErrorResponse> respondFramework(
            final org.springframework.web.ErrorResponse framework, final HttpServletRequest request) {
        final int statusCode = framework.getStatusCode().value();
        final HttpStatus resolved = HttpStatus.resolve(statusCode);
        final String code = resolved == null ? "HTTP_" + statusCode : resolved.name();
        final String message = framework.getBody().getDetail() == null
                ? framework.getBody().getTitle()
                : framework.getBody().getDetail();
        LOG.debug("{} on {}", code, request.getRequestURI());
        final ErrorResponse body =
                new ErrorResponse(code, message, statusCode, request.getRequestURI(), Instant.now(), List.of());
        return ResponseEntity.status(statusCode).body(body);
    }

    private static ResponseEntity<ErrorResponse> respond(final AppError error, final HttpServletRequest request) {
        final ErrorResponse body = ErrorResponse.of(error, request.getRequestURI(), Instant.now());
        return ResponseEntity.status(error.getStatusCode()).body(body);
    }

    private static String describe(final FieldError fieldError) {
        return fieldError.getField() + ": " + fieldError.getDefaultMessage();
    }
}
