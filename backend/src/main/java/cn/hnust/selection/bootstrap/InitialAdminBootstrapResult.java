package cn.hnust.selection.bootstrap;

/**
 * 首次总管理员初始化的单次交付结果。
 *
 * <p>这个对象只在一次性命令行启动期间短暂持有随机临时凭证；调用方应立即在受控终端展示，
 * 不得序列化到文件、日志、数据库或 HTTP 响应。数据库只保存该凭证的安全哈希。</p>
 */
public class InitialAdminBootstrapResult {
    private final Long accountId;
    private final String loginIdentifier;
    private final String collegeCode;
    private final String collegeName;
    private final String temporaryCredential;

    public InitialAdminBootstrapResult(Long accountId, String loginIdentifier, String collegeCode,
                                       String collegeName, String temporaryCredential) {
        this.accountId = accountId;
        this.loginIdentifier = loginIdentifier;
        this.collegeCode = collegeCode;
        this.collegeName = collegeName;
        this.temporaryCredential = temporaryCredential;
    }

    public Long getAccountId() { return accountId; }
    public String getLoginIdentifier() { return loginIdentifier; }
    public String getCollegeCode() { return collegeCode; }
    public String getCollegeName() { return collegeName; }

    /** 仅供一次性终端输出使用；调用结束后不要留存该明文。 */
    public String getTemporaryCredential() { return temporaryCredential; }

    /** 防止调试器或误加日志时把临时凭证明文写到日志。 */
    @Override
    public String toString() {
        return "InitialAdminBootstrapResult{" +
            "accountId=" + accountId +
            ", loginIdentifier='" + loginIdentifier + '\'' +
            ", collegeCode='" + collegeCode + '\'' +
            ", collegeName='" + collegeName + '\'' +
            ", temporaryCredential='[REDACTED]'" +
            '}';
    }
}
