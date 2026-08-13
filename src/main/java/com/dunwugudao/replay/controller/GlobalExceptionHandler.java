package com.dunwugudao.replay.controller;

import com.dunwugudao.replay.exception.NotConfiguredException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理：将"未配置"等受控异常转为结构化响应。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotConfiguredException.class)
    public ResponseEntity<Map<String, String>> handleNotConfigured(NotConfiguredException e) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("code", "NOT_CONFIGURED");
        body.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
