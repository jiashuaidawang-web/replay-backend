package com.dunwugudao.replay.exception;

/**
 * 功能尚未配置异常（如 S8 个人复盘 trade_log 表未创建）。
 * 由 {@code GlobalExceptionHandler} 统一转为 503 + 结构化提示。
 */
public class NotConfiguredException extends RuntimeException {

    public NotConfiguredException(String message) {
        super(message);
    }
}
