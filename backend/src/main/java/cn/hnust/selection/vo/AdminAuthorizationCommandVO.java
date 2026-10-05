package cn.hnust.selection.vo;

/**
 * 授权命令的简短回执。
 *
 * <p>回执字段保持稳定，允许同一 Idempotency-Key 的重试原样返回授权记录 ID 和完成动作，
 * 即使该记录后来又被撤销，也不会把第二次请求误报成一项新授权。</p>
 */
public class AdminAuthorizationCommandVO {
    private final Long authorizationId;
    private final String result;

    public AdminAuthorizationCommandVO(Long authorizationId, String result) {
        this.authorizationId = authorizationId;
        this.result = result;
    }

    public Long getAuthorizationId() {
        return authorizationId;
    }
    public String getResult() {
        return result;
    }
}
