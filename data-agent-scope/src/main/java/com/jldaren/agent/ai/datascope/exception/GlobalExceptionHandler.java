package com.jldaren.agent.ai.datascope.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.concurrent.TimeoutException;

// 添加全局异常处理
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGenericError(Exception e) {
        return ResponseEntity.status(500)
                .body(Map.of("error", "internal_error", "message", e.getMessage()));
    }

    @ExceptionHandler(TimeoutException.class)
    public ResponseEntity<Map<String, String>> handleTimeout(TimeoutException e) {
        return ResponseEntity.status(504)
                .body(Map.of("error", "timeout", "message", "请求超时，请稍后重试"));
    }
}
