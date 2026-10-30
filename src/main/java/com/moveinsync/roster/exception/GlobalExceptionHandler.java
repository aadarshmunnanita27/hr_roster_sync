package com.moveinsync.roster.exception;

import com.moveinsync.roster.config.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(RosterException.class)
    ResponseEntity<ApiError> handleRosterException(RosterException exception, HttpServletRequest request) {
        HttpStatus status = HttpStatus.valueOf(exception.httpStatus());
        ApiError body = new ApiError(
                exception.errorCode(),
                messageFor(exception.errorCode()),
                request.getHeader(CorrelationIdFilter.HEADER),
                exception.field());
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpectedException(Exception exception, HttpServletRequest request) {
        logger.error("request_failed correlationId={} errorCode=INTERNAL_ERROR exceptionType={}",
                request.getHeader(CorrelationIdFilter.HEADER), exception.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("INTERNAL_ERROR", "The request could not be completed.",
                        request.getHeader(CorrelationIdFilter.HEADER), null));
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<ApiError> handleBadRequest(Exception exception, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(new ApiError(
                "INVALID_REQUEST", "The request contains an invalid field.",
                request.getHeader(CorrelationIdFilter.HEADER), null));
    }

    @ExceptionHandler({
            CannotAcquireLockException.class,
            DataAccessResourceFailureException.class,
            QueryTimeoutException.class
    })
    ResponseEntity<ApiError> handleDependencyTimeout(Exception exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError("DEPENDENCY_UNAVAILABLE", "A required service is temporarily unavailable.",
                        request.getHeader(CorrelationIdFilter.HEADER), null));
    }

    private String messageFor(String errorCode) {
        return switch (errorCode) {
            case "TENANT_ACCESS_DENIED" -> "The requested resource is not available to this tenant.";
            case "EMPLOYEE_NOT_FOUND" -> "The requested employee was not found.";
            case "SNAPSHOT_NOT_FOUND" -> "The requested snapshot run was not found.";
            case "INVALID_PHONE", "INVALID_EMAIL", "INVALID_SHIFT", "INVALID_NAME",
                    "UNKNOWN_OFFICE", "UNKNOWN_FIELD", "TRANSPORT_FIELD_NOT_ALLOWED",
                    "INVALID_SOURCE_VERSION", "INVALID_EVENT_TYPE", "MISSING_REQUIRED_FIELD",
                    "SOURCE_SYSTEM_CONFLICT", "INVALID_EMPLOYEE_ID", "INVALID_SOURCE_SYSTEM" ->
                    "The record contains an invalid field.";
            default -> "The request could not be completed.";
        };
    }
}
