package cn.hnust.selection.common;

/**
 * HTTP API 的统一响应信封，字段固定为 code、message、data。
 * 成功响应使用 OK；可预期错误由异常处理器提供业务错误码、提示和可选详情。
 */
public class Result<T> {

    private String code;
    private String message;
    private T data;

    public Result() {
    }

    public Result(String code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> Result<T> success(T data) {
        // 成功数据统一放在 data 字段，前端 request<T> 会从信封中取出该值。
        return new Result<T>("OK", "操作成功", data);
    }

    public static <T> Result<T> failure(String code, String message, T data) {
        // 允许调用方携带字段校验等错误详情；没有额外详情时传 null。
        return new Result<T>(code, message, data);
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
