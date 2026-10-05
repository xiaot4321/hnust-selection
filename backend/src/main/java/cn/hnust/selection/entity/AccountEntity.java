package cn.hnust.selection.entity;

import java.sql.Timestamp;

/**
 * 账号表的持久化读取结果。
 *
 * <p>{@code loginIdentifier}、{@code roleCode} 和 {@code accountStatus} 描述账号身份与可用状态；
 * {@code passwordHash} 只供后端认证或凭证管理 Service 使用，禁止映射到任何 VO、日志或审计快照。
 * {@code mustChangePassword} 和 {@code credentialChangedAt} 用于首次登录改密流程，
 * {@code rowVersion} 用于检测账号的并发更新。</p>
 */
public class AccountEntity {
    private final Long id;
    private final String loginIdentifier;
    private final String roleCode;
    private final String accountStatus;
    private final String passwordHash;
    private final boolean mustChangePassword;
    private final Timestamp credentialChangedAt;
    private final long rowVersion;

    public AccountEntity(Long id, String loginIdentifier, String roleCode, String accountStatus,
                         String passwordHash, boolean mustChangePassword,
                         Timestamp credentialChangedAt, long rowVersion) {
        this.id = id;
        this.loginIdentifier = loginIdentifier;
        this.roleCode = roleCode;
        this.accountStatus = accountStatus;
        this.passwordHash = passwordHash;
        this.mustChangePassword = mustChangePassword;
        this.credentialChangedAt = credentialChangedAt;
        this.rowVersion = rowVersion;
    }

    public Long getId() {
        return id;
    }
    public String getLoginIdentifier() {
        return loginIdentifier;
    }
    public String getRoleCode() {
        return roleCode;
    }
    public String getAccountStatus() {
        return accountStatus;
    }
    public String getPasswordHash() {
        return passwordHash;
    }
    public boolean isMustChangePassword() {
        return mustChangePassword;
    }
    public Timestamp getCredentialChangedAt() {
        return credentialChangedAt;
    }
    public long getRowVersion() {
        return rowVersion;
    }
}
