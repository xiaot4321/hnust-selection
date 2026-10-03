package cn.hnust.selection.exception;

import cn.hnust.selection.common.Result;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import javax.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ControllerAdvice：把 Spring MVC 处理请求期间抛出的异常转换为统一 code/message/data 信封。
 * 可预期错误保留稳定业务码；未知异常统一返回 500，日志细节不写进客户端响应。
 * Spring Security 过滤器链发生在 ControllerAdvice 之外，由对应的 Security Handler 单独转换响应。
 */
@RestControllerAdvice
public class ApiExceptionHandler {
    /** 将 Service 主动声明的业务错误转换成其指定的 HTTP 状态与错误码。 */
    @ExceptionHandler(ApiException.class)
    public org.springframework.http.ResponseEntity<Result<?>> handleApiException(ApiException exception) {
        return response(exception.getStatus(), exception.getCode(), exception.getMessage(), null);
    }

    /** 汇总请求 DTO 的 Bean Validation 错误，只返回字段名和原因码，不回传用户输入值。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public org.springframework.http.ResponseEntity<Result<?>> handleValidation(MethodArgumentNotValidException exception) {
        // 字段错误只返回字段名和稳定原因码，不回传用户输入的凭证内容。
        List<Map<String, String>> fieldErrors = new ArrayList<Map<String, String>>();
        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            Map<String, String> error = new LinkedHashMap<String, String>();
            error.put("field", fieldError.getField());
            error.put("reason", "INVALID_VALUE");
            fieldErrors.add(error);
        }
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("fieldErrors", fieldErrors);
        return response(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "请求参数不合法", data);
    }

    /** JSON、路径/查询参数、必填请求头或方法参数约束不合法时返回统一的参数错误。 */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
        MissingRequestHeaderException.class, MissingServletRequestParameterException.class,
        ConstraintViolationException.class})
    public org.springframework.http.ResponseEntity<Result<?>> handleInvalidRequest(Exception exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "请求格式或参数不合法", null);
    }

    /** 禁用账号通过认证阶段时仍被拒绝；账号状态对应专用错误码。 */
    @ExceptionHandler(DisabledException.class)
    public org.springframework.http.ResponseEntity<Result<?>> handleDisabled(DisabledException exception) {
        return response(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "账号已停用", null);
    }

    /** 登录标识或密码不正确时使用统一提示，不区分具体是哪个字段失败。 */
    @ExceptionHandler(BadCredentialsException.class)
    public org.springframework.http.ResponseEntity<Result<?>> handleBadCredentials(BadCredentialsException exception) {
        return response(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "登录标识或密码不正确", null);
    }

    /** 兜底转换其他认证异常，避免 Spring 默认返回 HTML 或暴露框架信息。 */
    @ExceptionHandler(AuthenticationException.class)
    public org.springframework.http.ResponseEntity<Result<?>> handleAuthentication(AuthenticationException exception) {
        return response(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "登录标识或密码不正确", null);
    }

    /** Controller/方法级访问控制拒绝时返回统一 403。 */
    @ExceptionHandler(AccessDeniedException.class)
    public org.springframework.http.ResponseEntity<Result<?>> handleAccessDenied(AccessDeniedException exception) {
        return response(HttpStatus.FORBIDDEN, "FORBIDDEN", "当前账号无权执行此操作", null);
    }

    /** 未分类异常不向客户端暴露异常类名、SQL 或堆栈。 */
    @ExceptionHandler(Exception.class)
    public org.springframework.http.ResponseEntity<Result<?>> handleUnexpected(Exception exception) {
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务暂时无法处理请求", null);
    }

    private org.springframework.http.ResponseEntity<Result<?>> response(HttpStatus status, String code,
                                                                         String message, Object data) {
        // 所有处理分支通过同一出口设置 HTTP 状态并序列化相同结构，避免错误响应格式漂移。
        return org.springframework.http.ResponseEntity.status(status)
            .body(Result.failure(code, message, data));
    }
}
