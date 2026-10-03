package cn.hnust.selection.exception;

import org.springframework.http.HttpStatus;

/**
 * Service 可主动抛出的可预期业务/认证错误。
 * 稳定 code 用于前端区分错误原因，HttpStatus 决定 HTTP 状态；异常处理器会将二者写入统一 Result。
 * 不应把数据库异常或调试堆栈包装进 message 回传给客户端。
 */
public class ApiException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public ApiException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String getCode() { return code; }
    public HttpStatus getStatus() { return status; }
}
