package cn.hnust.selection.vo;

/** 管理员账号列表中的最小必要信息；不包含密码、临时凭证或人员资料。 */
public class AdminAccountDirectoryItemVO {
    private final Long accountId;
    private final String loginIdentifier;
    private final String accountStatus;
    private final boolean mustChangePassword;
    private final String createdAt;

    public AdminAccountDirectoryItemVO(Long accountId, String loginIdentifier, String accountStatus,
                                             boolean mustChangePassword, String createdAt) {
        this.accountId = accountId;
        this.loginIdentifier = loginIdentifier;
        this.accountStatus = accountStatus;
        this.mustChangePassword = mustChangePassword;
        this.createdAt = createdAt;
    }

    public Long getAccountId() {
        return accountId;
    }
    public String getLoginIdentifier() {
        return loginIdentifier;
    }
    public String getAccountStatus() {
        return accountStatus;
    }
    public boolean isMustChangePassword() {
        return mustChangePassword;
    }
    public String getCreatedAt() {
        return createdAt;
    }
}
