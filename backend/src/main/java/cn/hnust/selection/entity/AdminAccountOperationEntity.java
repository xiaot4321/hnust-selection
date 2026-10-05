package cn.hnust.selection.entity;

/**
 * 管理员账号创建或凭证重置命令的幂等查询投影。
 *
 * <p>{@code fingerprint} 用于检查同一个幂等键是否被不同请求复用；其余字段是已完成操作的安全回执。
 * {@code expiresAt} 保留为兼容字段；当前凭证不设到期时间时该值为 {@code null}，明文不保存在该实体中。</p>
 */
public class AdminAccountOperationEntity {
    private final Long operationId;
    private final String fingerprint;
    private final String resultCode;
    private final Long accountId;
    private final String loginIdentifier;
    private final String expiresAt;

    public AdminAccountOperationEntity(Long operationId, String fingerprint, String resultCode,
                                       Long accountId, String loginIdentifier, String expiresAt) {
        this.operationId = operationId;
        this.fingerprint = fingerprint;
        this.resultCode = resultCode;
        this.accountId = accountId;
        this.loginIdentifier = loginIdentifier;
        this.expiresAt = expiresAt;
    }

    public Long getOperationId() {
        return operationId;
    }
    public String getFingerprint() {
        return fingerprint;
    }
    public String getResultCode() {
        return resultCode;
    }
    public Long getAccountId() {
        return accountId;
    }
    public String getLoginIdentifier() {
        return loginIdentifier;
    }
    public String getExpiresAt() {
        return expiresAt;
    }
}
