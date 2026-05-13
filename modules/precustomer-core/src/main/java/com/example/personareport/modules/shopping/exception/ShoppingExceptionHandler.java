package com.example.personareport.modules.shopping.exception;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice(basePackages = "com.example.personareport.modules.shopping")
public class ShoppingExceptionHandler {

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Map<String, Object> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b).orElse("입력값이 올바르지 않습니다.");
        return Map.of("error", "VALIDATION_FAILED", "message", msg);
    }

    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    @ExceptionHandler(NaverApiRateLimitException.class)
    public Map<String, Object> handleRateLimit(NaverApiRateLimitException e) {
        return Map.of("error", "RATE_LIMITED", "message", e.getMessage());
    }

    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(Exception.class)
    public Map<String, Object> handleGeneral(Exception e) {
        log.error("Shopping API 오류: {}", e.getMessage(), e);
        return Map.of("error", "INTERNAL_ERROR", "message", "요청 처리 중 오류가 발생했습니다.");
    }

    public static class NaverApiRateLimitException extends RuntimeException {
        public NaverApiRateLimitException(String msg) { super(msg); }
    }
}
