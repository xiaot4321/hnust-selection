package cn.hnust.selection.entity;

/**
 * 管理员目录的安全查询投影。
 *
 * <p>只映射列表需要的账号主键、登录标识、状态、首次改密标志和创建时间，不包含密码哈希、
 * 临时凭证或其他角色的人员资料。对外响应仍由 Service 映射为 {@code AdminAccountDirectoryItemVO}。</p>
 */
public class AdminAccountEntity {
    private final Long accountId;
    private final String loginIdentifier;
    private final String accountStatus;
    private final boolean mustChangePassword;
    private final String createdAt;

    public AdminAccountEntity(Long accountId, String loginIdentifier, String accountStatus,
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
