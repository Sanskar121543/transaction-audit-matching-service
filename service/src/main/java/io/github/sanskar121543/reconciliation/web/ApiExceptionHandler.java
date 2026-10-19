package io.github.sanskar121543.reconciliation.web;

import io.github.sanskar121543.reconciliation.ingest.MalformedFileException;
import io.github.sanskar121543.reconciliation.ingest.PayloadTooLargeException;
import java.net.URI;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Problems.of(HttpStatus.NOT_FOUND, e.type(), "Not found", e.getMessage()));
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ProblemDetail> conflict(ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Problems.of(HttpStatus.CONFLICT, e.type(), "Conflict", e.getMessage()));
    }

    @ExceptionHandler(MalformedFileException.class)
    ResponseEntity<ProblemDetail> malformedFile(MalformedFileException e) {
        return ResponseEntity.badRequest()
                .body(Problems.of(HttpStatus.BAD_REQUEST, "malformed-file", "The file could not be read",
                        e.getMessage()));
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    ResponseEntity<ProblemDetail> tooLarge(PayloadTooLargeException e) {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body(Problems.of(HttpStatus.CONTENT_TOO_LARGE, "too-large", "Upload too large", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> invalidArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(Problems.of(HttpStatus.BAD_REQUEST, "validation", "Request validation failed", e.getMessage()));
    }

    // No connection within Hikari's connection-timeout, or the database is unreachable. Nothing the
    // request did was committed unless the failure hit the commit itself; uploads are safe to retry.
    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class,
            TransientDataAccessResourceException.class})
    ResponseEntity<ProblemDetail> databaseUnavailable(Exception e) {
        log.warn("Database unavailable: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(Problems.of(HttpStatus.SERVICE_UNAVAILABLE, "database-unavailable", "Database unavailable",
                        "The database could not be reached in time; retry the request."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal error", null));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<String> errors = ex.getBindingResult().getAllErrors().stream()
                .map(error -> describe(null, error))
                .sorted()
                .toList();
        return handleExceptionInternal(ex, validationProblem(errors), headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<String> errors = ex.getParameterValidationResults().stream()
                .flatMap(ApiExceptionHandler::errorsOf)
                .sorted()
                .toList();
        return handleExceptionInternal(ex, validationProblem(errors), headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = Problems.of(HttpStatus.BAD_REQUEST, "malformed-request", "Malformed request body",
                "The body must be JSON with exactly the documented fields.");
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem
                && (problem.getType() == null || "about:blank".equals(problem.getType().toString()))) {
            problem.setType(URI.create("/problems/" + (statusCode.is4xxClientError() ? "bad-request" : "server-error")));
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    private static ProblemDetail validationProblem(List<String> errors) {
        ProblemDetail problem = Problems.of(HttpStatus.BAD_REQUEST, "validation", "Request validation failed", null);
        problem.setProperty("errors", errors);
        return problem;
    }

    private static Stream<String> errorsOf(ParameterValidationResult result) {
        String parameter = result.getMethodParameter().getParameterName();
        return result.getResolvableErrors().stream().map(error -> describe(parameter, error));
    }

    private static String describe(String parameter, MessageSourceResolvable error) {
        if (error instanceof FieldError field) {
            return field.getField() + ": " + field.getDefaultMessage();
        }
        return parameter == null ? error.getDefaultMessage() : parameter + ": " + error.getDefaultMessage();
    }
}
