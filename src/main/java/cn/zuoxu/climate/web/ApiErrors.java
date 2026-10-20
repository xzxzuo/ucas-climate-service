package cn.zuoxu.climate.web;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger log=LoggerFactory.getLogger(ApiErrors.class);
    @ExceptionHandler({IllegalArgumentException.class,ConstraintViolationException.class,MethodArgumentTypeMismatchException.class,MissingServletRequestParameterException.class,HttpMessageNotReadableException.class,MissingServletRequestPartException.class})
    public ResponseEntity<?> invalid(Exception e) { return error(400,"INVALID_ARGUMENT",e.getMessage()); }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> status(ResponseStatusException e) { return error(e.getStatusCode().value(),"REQUEST_FAILED",e.getReason()); }
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<?> missing(NoResourceFoundException e) { return error(404,"NOT_FOUND","No such resource"); }
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<?> duplicate(DuplicateKeyException e) {return error(409,"DUPLICATE_RESOURCE","Resource already exists; refresh and retry");}
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> tooLarge(MaxUploadSizeExceededException e) {return error(413,"UPLOAD_TOO_LARGE","CSV upload must not exceed 8 MiB");}
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> database(DataAccessException e) { log.error("Database operation failed",e);return error(503,"DATABASE_UNAVAILABLE","Database operation failed; see server log with requestId"); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unexpected(Exception e) { log.error("Request failed",e);return error(500,"INTERNAL_ERROR","Request failed; see server log with requestId"); }
    private ResponseEntity<?> error(int status,String code,String message) {
        return ResponseEntity.status(status).header("Content-Type","application/json;charset=UTF-8")
            .body(Map.of("error",code,"message",message==null?"":message,"requestId",String.valueOf(MDC.get("requestId"))));
    }
}
