package com.udcf.web;

import com.udcf.core.cluster.NodeDownException;
import com.udcf.core.cluster.UnknownNodeException;
import com.udcf.core.module.ModuleBusyException;
import com.udcf.core.module.UnknownModuleException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps UDCF exceptions to RFC 7807 {@link ProblemDetail} responses.
 *
 * <p>Covers every controller under {@code com.udcf}, including each module's controller in
 * {@code com.udcf.modules.<moduleId>}. Every body carries {@code status}, {@code title},
 * {@code detail} and {@code instance}; node and module errors add {@code nodeId} or
 * {@code moduleId}, and parameter or request-body errors add {@code errors: {name: message}}.</p>
 */
@RestControllerAdvice(basePackages = "com.udcf")
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    static final String INVALID_PARAMETERS_TITLE = "Invalid request parameters";

    @ExceptionHandler(UnknownNodeException.class)
    public ProblemDetail unknownNode(UnknownNodeException e) {
        return problem(HttpStatus.NOT_FOUND, "Unknown node", e.getMessage(), "nodeId", e.nodeId());
    }

    @ExceptionHandler(UnknownModuleException.class)
    public ProblemDetail unknownModule(UnknownModuleException e) {
        return problem(HttpStatus.NOT_FOUND, "Unknown module", e.getMessage(), "moduleId", e.moduleId());
    }

    @ExceptionHandler(NodeStateConflictException.class)
    public ProblemDetail nodeStateConflict(NodeStateConflictException e) {
        return problem(HttpStatus.CONFLICT, "Node state conflict", e.getMessage(), "nodeId", e.nodeId());
    }

    @ExceptionHandler(NodeDownException.class)
    public ProblemDetail nodeDown(NodeDownException e) {
        return problem(HttpStatus.CONFLICT, "Node down", e.getMessage(), "nodeId", e.nodeId());
    }

    @ExceptionHandler(ModuleBusyException.class)
    public ProblemDetail moduleBusy(ModuleBusyException e) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "Module busy", e.getMessage(), "moduleId", e.moduleId());
        problem.setProperty("actionInProgress", e.actionInProgress());
        return problem;
    }

    @ExceptionHandler(InvalidParameterException.class)
    public ProblemDetail invalidParameter(InvalidParameterException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Invalid request parameter: " + e.parameter());
        problem.setTitle(INVALID_PARAMETERS_TITLE);
        problem.setProperty("errors", Map.of(e.parameter(), e.getMessage()));
        return problem;
    }

    /** Annotation-based parameter validation, such as {@code @Min} on a request parameter. */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (ParameterValidationResult result : ex.getAllValidationResults()) {
            String parameter = result.getMethodParameter().getParameterName();
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                errors.putIfAbsent(parameter, error.getDefaultMessage());
            }
        }
        ProblemDetail body = ex.getBody();
        body.setTitle(INVALID_PARAMETERS_TITLE);
        body.setDetail("Invalid request parameter: " + String.join(", ", errors.keySet()));
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    /** A {@code @Valid} request body that fails validation: the same 400 shape as a bad parameter. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        for (ObjectError error : ex.getBindingResult().getGlobalErrors()) {
            errors.putIfAbsent(error.getObjectName(), error.getDefaultMessage());
        }
        ProblemDetail body = ex.getBody();
        body.setTitle(INVALID_PARAMETERS_TITLE);
        body.setDetail("Invalid request body: " + String.join(", ", errors.keySet()));
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail, String key, Object value) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setProperty(key, value);
        return problem;
    }
}
