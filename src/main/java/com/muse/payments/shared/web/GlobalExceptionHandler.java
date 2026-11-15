package com.muse.payments.shared.web;

import com.muse.payments.shared.domain.ConflictException;
import com.muse.payments.shared.domain.EntityNotFoundException;
import com.muse.payments.shared.domain.ForbiddenException;
import com.muse.payments.shared.domain.OptimisticConcurrencyException;
import com.muse.payments.shared.domain.RateLimitExceededException;
import com.muse.payments.shared.domain.ValidationException;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(EntityNotFoundException.class)
    public ProblemDetail handleNotFound(EntityNotFoundException ex, HttpServletRequest request) {
        return createProblemDetail(HttpStatus.NOT_FOUND, "Resource Not Found", ex.getMessage(), request);
    }

    @ExceptionHandler(ConflictException.class)
    public ProblemDetail handleConflict(ConflictException ex, HttpServletRequest request) {
        return createProblemDetail(HttpStatus.CONFLICT, "Conflict", ex.getMessage(), request);
    }

    @ExceptionHandler({
            OptimisticLockingFailureException.class,
            OptimisticLockException.class,
            OptimisticConcurrencyException.class
    })
    public ProblemDetail handleOptimisticLock(Exception ex, HttpServletRequest request) {
        log.warn("Concurrent modification at {} correlationId={}", request.getRequestURI(), correlationId());
        ProblemDetail problem = createProblemDetail(HttpStatus.CONFLICT, "Concurrent Modification", "The resource was modified concurrently. Please retry.", request);
        problem.setType(URI.create("urn:problem-type:concurrent-modification"));
        return problem;
    }

    // Spring translates SQLState 40001 to CannotAcquireLockException and 40P01 (and the
    // rest of class 40) to PessimisticLockingFailureException. Both are transient: the same
    // request can succeed, so this is 503 with Retry-After. Optimistic 409 means reload.
    @ExceptionHandler({PessimisticLockingFailureException.class, CannotAcquireLockException.class})
    public ResponseEntity<ProblemDetail> handleTransientLock(Exception ex, HttpServletRequest request) {
        log.warn("Transient database lock at {} correlationId={}", request.getRequestURI(), correlationId());
        ProblemDetail problem = createProblemDetail(HttpStatus.SERVICE_UNAVAILABLE, "Transient Lock Failure", "The database could not acquire a lock. Retry the same request.", request);
        problem.setType(URI.create("urn:problem-type:transient-lock"));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "1")
                .body(problem);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("Data integrity violation: {}", ex.getMessage());
        return createProblemDetail(HttpStatus.CONFLICT, "Data Conflict", "Operation violates data integrity constraints.", request);
    }

    @ExceptionHandler(ValidationException.class)
    public ProblemDetail handleValidation(ValidationException ex, HttpServletRequest request) {
        return createProblemDetail(HttpStatus.BAD_REQUEST, "Validation Error", ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String name = ex.getName() == null ? "parameter" : ex.getName();
        return createProblemDetail(
                HttpStatus.BAD_REQUEST,
                "Validation Error",
                "Invalid value for parameter '" + name + "'",
                request
        );
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimit(RateLimitExceededException ex, HttpServletRequest request) {
        ProblemDetail problem = createProblemDetail(HttpStatus.TOO_MANY_REQUESTS, "Rate Limit Exceeded", ex.getMessage(), request);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpServletRequest request) {
        ProblemDetail problem = createProblemDetail(HttpStatus.BAD_REQUEST, "Validation Error", "Invalid request parameters.", request);
        Map<String, List<String>> fieldErrors = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.computeIfAbsent(error.getField(), k -> new ArrayList<>()).add(error.getDefaultMessage());
        }
        problem.setProperty("invalidParams", fieldErrors);
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleNotReadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return createProblemDetail(HttpStatus.BAD_REQUEST, "Malformed Request", "Malformed JSON request body.", request);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ProblemDetail handleForbidden(ForbiddenException ex, HttpServletRequest request) {
        return createProblemDetail(HttpStatus.FORBIDDEN, "Forbidden", ex.getMessage(), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return createProblemDetail(HttpStatus.FORBIDDEN, "Forbidden", "Access is denied.", request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        return createProblemDetail(HttpStatus.UNAUTHORIZED, "Unauthorized", "Authentication is required or credentials are invalid.", request);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception at URI {}: ", request.getRequestURI(), ex);
        return createProblemDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "An unexpected error occurred.", request);
    }

    public static ProblemDetail createProblemDetail(
            HttpStatus status,
            String title,
            String detail,
            HttpServletRequest request
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create("urn:problem-type:" + status.value()));
        if (request != null) {
            problem.setInstance(URI.create(request.getRequestURI()));
        }

        String correlationId = CorrelationContext.get();
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
        return problem;
    }

    private static String correlationId() {
        String correlationId = CorrelationContext.get();
        return correlationId != null ? correlationId : "none";
    }
}
