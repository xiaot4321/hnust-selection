package cn.hnust.selection.entity;

/**
 * 管理员能力授予或撤销命令的幂等查询投影。
 *
 * <p>{@code fingerprint} 校验请求内容是否与首次命令一致；{@code authorizationId} 用于恢复稳定回执，
 * 不表示授权当前仍有效。</p>
 */
public class AdminAuthorizationOperationEntity {
    private final String fingerprint;
    private final String resultCode;
    private final Long authorizationId;

    public AdminAuthorizationOperationEntity(String fingerprint, String resultCode, Long authorizationId) {
        this.fingerprint = fingerprint;
        this.resultCode = resultCode;
        this.authorizationId = authorizationId;
    }

    public String getFingerprint() {
        return fingerprint;
    }
    public String getResultCode() {
        return resultCode;
    }
    public Long getAuthorizationId() {
        return authorizationId;
    }
}
