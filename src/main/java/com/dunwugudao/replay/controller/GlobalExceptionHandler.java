package com.dunwugudao.replay.controller;

import com.dunwugudao.replay.exception.NotConfiguredException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理：受控异常统一转为结构化响应，避免 Spring 默认错误页/空 500。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 未配置（如某 skill 数据源不可用）→ 503。 */
    @ExceptionHandler(NotConfiguredException.class)
    public ResponseEntity<Map<String, String>> handleNotConfigured(NotConfiguredException e) {
        return body(HttpStatus.SERVICE_UNAVAILABLE, "NOT_CONFIGURED", e.getMessage());
    }

    /** 参数类型不匹配（如 date=abc / date=2026-13-99）→ 400 友好提示。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String name = e.getName() != null ? e.getName() : "参数";
        String value = e.getValue() != null ? String.valueOf(e.getValue()) : "null";
        return body(HttpStatus.BAD_REQUEST, "INVALID_PARAM",
                String.format("参数 %s 的值 '%s' 不合法（应为 %s），示例：date=2026-08-14",
                        name, value, friendlyType(e)));
    }

    /** 缺少必填参数 → 400。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, String>> handleMissingParam(MissingServletRequestParameterException e) {
        return body(HttpStatus.BAD_REQUEST, "MISSING_PARAM",
                String.format("缺少必填参数 %s（类型：%s）", e.getParameterName(), e.getParameterType()));
    }

    /** 业务参数不合法（如 orderBy 不在白名单）→ 400。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, "INVALID_PARAM", e.getMessage());
    }

    /** 兜底 → 500。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleOther(Exception e) {
        log.error("未处理异常", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务内部错误，请稍后重试");
    }

    private String friendlyType(MethodArgumentTypeMismatchException e) {
        Class<?> t = e.getRequiredType();
        if (t != null) {
            if (java.time.LocalDate.class.isAssignableFrom(t)) {
                return "yyyy-MM-dd 日期";
            }
            return t.getSimpleName();
        }
        return "合法值";
    }

    private ResponseEntity<Map<String, String>> body(HttpStatus status, String code, String message) {
        Map<String, String> b = new LinkedHashMap<>();
        b.put("code", code);
        b.put("message", message);
        return ResponseEntity.status(status).body(b);
    }
}
