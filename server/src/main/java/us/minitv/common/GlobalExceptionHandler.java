package us.minitv.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FastAPI-shaped error responses: every failure is {@code {"detail": ...}}.
 *
 * Request-validation failures mirror Pydantic's 422 payload closely enough for
 * the clients: an array of {loc, msg, type}. The admin SPA only reads
 * {@code detail} when it is a string or an object, so the array is a fallback
 * shape for form errors.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException ex) {
        HttpHeaders headers = new HttpHeaders();
        ex.headers().forEach(headers::set);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(body(ex.detail()), headers, HttpStatus.valueOf(ex.status()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        List<Map<String, Object>> errors = new ArrayList<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "value_error");
            item.put("loc", List.of("body", error.getField()));
            item.put("msg", error.getDefaultMessage());
            errors.add(item);
        }
        return ResponseEntity.status(422).body(body(errors));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException ex) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "missing");
        item.put("loc", List.of("query", ex.getParameterName()));
        item.put("msg", "Field required");
        return ResponseEntity.status(422).body(body(List.of(item)));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "type_error");
        item.put("loc", List.of("query", ex.getName()));
        item.put("msg", "Input should be a valid value");
        return ResponseEntity.status(422).body(body(List.of(item)));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "json_invalid");
        item.put("loc", List.of("body"));
        item.put("msg", "JSON decode error");
        return ResponseEntity.status(422).body(body(List.of(item)));
    }

    @ExceptionHandler({NoResourceFoundException.class, org.springframework.web.servlet.NoHandlerFoundException.class})
    public ResponseEntity<Map<String, Object>> handleNoResource(Exception ex) {
        return ResponseEntity.status(404).body(body("Not Found"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethod(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(405).body(body("Method Not Allowed"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnhandled(HttpServletRequest request, Exception ex) {
        log.error("unhandled error on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity.status(500).body(body("Internal Server Error"));
    }

    private static Map<String, Object> body(Object detail) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("detail", detail);
        return payload;
    }
}
