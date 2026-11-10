package org.opensrm.foundation;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.security.access.AccessDeniedException;

@RestControllerAdvice
public class Problems {
    public static Map<String, Object> body(int status, String code) {
        return Map.of("status", status, "code", code, "title", code, "requestId", RequestIds.current().toString());
    }
    public static void write(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        JsonMapper.builder().build().writeValue(response.getOutputStream(), body(status, code));
    }
    @ExceptionHandler(ApiProblem.class)
    ResponseEntity<?> handle(ApiProblem error) {
        return ResponseEntity.status(error.status).header("Content-Type", "application/problem+json").body(body(error.status, error.code));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
                       MethodArgumentTypeMismatchException.class, IllegalArgumentException.class,
                       ConstraintViolationException.class, MissingRequestHeaderException.class, java.time.DateTimeException.class})
    ResponseEntity<?> invalid(Exception ignored) {
        return ResponseEntity.badRequest().header("Content-Type", "application/problem+json").body(body(400, "VALIDATION_FAILED"));
    }
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<?> denied(AccessDeniedException ignored) { return handle(new ApiProblem(403,"FORBIDDEN")); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception error) {
        if (error instanceof org.springframework.web.ErrorResponse expected) {
            int status=expected.getStatusCode().value();
            return handle(new ApiProblem(status,status==404?"NOT_FOUND":"INVALID_HTTP_REQUEST"));
        }
        if (error instanceof org.springframework.dao.DataAccessException) return handle(new ApiProblem(503,"DEPENDENCY_UNAVAILABLE"));
        org.slf4j.LoggerFactory.getLogger(Problems.class).error("event=request_failed requestId={} type={}",RequestIds.current(),error.getClass().getSimpleName());
        return handle(new ApiProblem(500,"INTERNAL_ERROR"));
    }
}
