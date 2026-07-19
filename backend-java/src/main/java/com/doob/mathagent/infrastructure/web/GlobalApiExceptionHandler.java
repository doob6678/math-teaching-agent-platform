package com.doob.mathagent.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Centralizes JSON error shaping and backend logging so the frontend receives readable errors with a trace id.
 */
@RestControllerAdvice
public class GlobalApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalApiExceptionHandler.class);

    /**
     * Handles controller-thrown HTTP exceptions without losing the original status code.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleResponseStatus(
            ResponseStatusException exception,
            HttpServletRequest request) {
        String traceId = traceId();
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        log.warn("api_error traceId={} status={} path={} message={}",
                traceId,
                status.value(),
                request.getRequestURI(),
                safe(exception.getReason()),
                exception);
        return ResponseEntity.status(status).body(new ApiErrorResponse(
                code(status),
                message(status, safe(exception.getReason())),
                traceId,
                request.getRequestURI()));
    }

    /**
     * Converts servlet-level multipart size failures into a readable 400 instead of a generic 500.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleMaxUploadSize(
            MaxUploadSizeExceededException exception,
            HttpServletRequest request) {
        String traceId = traceId();
        log.warn("api_upload_too_large traceId={} path={} message={}",
                traceId,
                request.getRequestURI(),
                safe(exception.getMessage()),
                exception);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiErrorResponse(
                "BAD_REQUEST",
                "上传文件超过后端大小限制，请改用更小文件、拆分上传，或提高后端 multipart 限制。",
                traceId,
                request.getRequestURI()));
    }

    /** Converts caller validation failures, including MCP library selection, into actionable 400 responses. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(
            IllegalArgumentException exception,
            HttpServletRequest request) {
        String traceId = traceId();
        log.warn("api_bad_request traceId={} path={} message={}", traceId, request.getRequestURI(), safe(exception.getMessage()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiErrorResponse(
                "BAD_REQUEST", safe(exception.getMessage()), traceId, request.getRequestURI()));
    }

    /**
     * Handles uncaught runtime failures and logs the full stack for later diagnosis.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(
            Exception exception,
            HttpServletRequest request) {
        String traceId = traceId();
        log.error("api_unexpected_error traceId={} path={} type={} message={}",
                traceId,
                request.getRequestURI(),
                exception.getClass().getSimpleName(),
                safe(exception.getMessage()),
                exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiErrorResponse(
                "INTERNAL_SERVER_ERROR",
                "服务内部处理失败，请稍后重试。",
                traceId,
                request.getRequestURI()));
    }

    /**
     * Uses one short UUID per response so UI and logs can be correlated without leaking internals.
     */
    private static String traceId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Maps HTTP status to a stable code consumed by the frontend.
     */
    private static String code(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "BAD_REQUEST";
            case UNAUTHORIZED -> "UNAUTHORIZED";
            case FORBIDDEN -> "FORBIDDEN";
            case NOT_FOUND -> "NOT_FOUND";
            case CONFLICT -> "CONFLICT";
            case TOO_MANY_REQUESTS -> "TOO_MANY_REQUESTS";
            case SERVICE_UNAVAILABLE -> "SERVICE_UNAVAILABLE";
            default -> "REQUEST_FAILED";
        };
    }

    /**
     * Keeps end-user messages short and readable while preserving explicit controller reasons.
     */
    private static String message(HttpStatus status, String reason) {
        if (!reason.isBlank()) {
            return reason;
        }
        return switch (status) {
            case BAD_REQUEST -> "请求参数不正确。";
            case UNAUTHORIZED -> "登录状态无效，请重新登录。";
            case FORBIDDEN -> "当前账号没有执行该操作的权限。";
            case NOT_FOUND -> "请求的资源不存在。";
            case TOO_MANY_REQUESTS -> "当前请求过于频繁，请稍后重试。";
            case SERVICE_UNAVAILABLE -> "服务暂时不可用，请稍后重试。";
            default -> "请求处理失败。";
        };
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
