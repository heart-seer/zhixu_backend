package com.zhixu.web;

import com.zhixu.model.entity.Result;
import com.zhixu.service.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Result> api(ApiException ex) {
        return ResponseEntity.status(ex.getStatus()).body(Result.failure(ex.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<Result> invalid(Exception ex) {
        FieldError fieldError = ex instanceof MethodArgumentNotValidException
                ? ((MethodArgumentNotValidException) ex).getBindingResult().getFieldError()
                : ((BindException) ex).getBindingResult().getFieldError();
        String message = fieldError == null ? "请求参数不合法" : fieldError.getDefaultMessage();
        return ResponseEntity.badRequest().body(Result.failure(message));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result> constraint(ConstraintViolationException ex) {
        String message = "请求参数不合法";
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            message = violation.getMessage();
            break;
        }
        return ResponseEntity.badRequest().body(Result.failure(message));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<Result> malformed(Exception ex) {
        return ResponseEntity.badRequest().body(Result.failure("请求参数不合法"));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Result> duplicate(DuplicateKeyException ex) {
        return ResponseEntity.status(409).body(Result.failure("数据已存在"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Result> integrity(DataIntegrityViolationException ex) {
        return ResponseEntity.status(409).body(Result.failure("数据约束冲突"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result> unexpected(Exception ex) {
        log.error("未处理的异常", ex);
        return ResponseEntity.status(500).body(Result.failure("服务器内部错误"));
    }
}
