package com.x.backendvoip.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApi(ApiException error, HttpServletRequest request) {
        return response(error.getStatus(), error.getCode(), error.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException error, HttpServletRequest request) {
        var problem = ApiErrors.problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Request validation failed.", request.getRequestURI());
        var errors = new LinkedHashMap<String, String>();
        error.getBindingResult().getFieldErrors().forEach(field -> errors.putIfAbsent(field.getField(), field.getDefaultMessage()));
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    ResponseEntity<ProblemDetail> handleBadRequest(Exception error, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Malformed JSON, unknown field or invalid parameter type.", request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleConflict(DataIntegrityViolationException error, HttpServletRequest request) {
        // Also handles concurrent registrations that both pass the initial existence check.
        return response(HttpStatus.CONFLICT, "ACCOUNT_CONFLICT", "Username or email is already registered.", request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(NoResourceFoundException error, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found.", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> handleMethod(HttpRequestMethodNotSupportedException error, HttpServletRequest request) {
        return response(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "HTTP method is not supported.", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception error, HttpServletRequest request) {
        log.error("Unhandled API error", error);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred.", request);
    }

    private ResponseEntity<ProblemDetail> response(HttpStatus status, String code, String detail, HttpServletRequest request) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ApiErrors.problem(status, code, detail, request.getRequestURI()));
    }
}
