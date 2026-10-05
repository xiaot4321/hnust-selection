package cn.hnust.selection.vo;

/**
 * 管理员账号创建/临时凭证重置回执。
 * 临时凭证明文只在首次完成请求的响应中存在；数据库和操作审计只保存哈希及凭证元数据。
 */
public class AdminAccountCredentialVO {
    private final Long accountId;
    private final String loginIdentifier;
    private final String result;
    private final String temporaryCredential;
    /** 兼容字段；当前凭证不设到期时间，响应为 null。 */
    private final String expiresAt;
    private final boolean credentialShownNow;

    public AdminAccountCredentialVO(Long accountId, String loginIdentifier, String result,
                                          String temporaryCredential, String expiresAt,
                                          boolean credentialShownNow) {
        this.accountId = accountId;
        this.loginIdentifier = loginIdentifier;
        this.result = result;
        this.temporaryCredential = temporaryCredential;
        this.expiresAt = expiresAt;
        this.credentialShownNow = credentialShownNow;
    }

    public Long getAccountId() {
        return accountId;
    }
    public String getLoginIdentifier() {
        return loginIdentifier;
    }
    public String getResult() {
        return result;
    }
    public String getTemporaryCredential() {
        return temporaryCredential;
    }
    public String getExpiresAt() {
        return expiresAt;
    }
    public boolean isCredentialShownNow() {
        return credentialShownNow;
    }

    /** 防止调试器或误加日志时把临时凭证明文写入日志。 */
    @Override
    public String toString() {
        return "AdminAccountCredentialVO{" +
            "accountId=" + accountId +
            ", loginIdentifier='" + loginIdentifier + '\'' +
            ", result='" + result + '\'' +
            ", temporaryCredential='[REDACTED]'" +
            ", expiresAt='" + expiresAt + '\'' +
            ", credentialShownNow=" + credentialShownNow +
            '}';
    }
}
