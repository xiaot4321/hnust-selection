package cn.hnust.selection.entity;

/**
 * 临时凭证认证查询投影。
 *
 * <p>{@code credentialHash} 是用于密码校验的单向哈希；{@code used}、{@code revoked} 和 {@code notExpired}
 * 是数据库查询得到的可用性状态。到期时间为空时 {@code notExpired} 仍为 true；Service 必须同时检查这些状态，
 * 只有未使用、未撤销且未处于历史到期状态的凭证才能通过认证。</p>
 */
public class TemporaryCredentialEntity {
    private final Long id;
    private final String credentialHash;
    private final boolean used;
    private final boolean revoked;
    private final boolean notExpired;

    public TemporaryCredentialEntity(Long id, String credentialHash, boolean used,
                                     boolean revoked, boolean notExpired) {
        this.id = id;
        this.credentialHash = credentialHash;
        this.used = used;
        this.revoked = revoked;
        this.notExpired = notExpired;
    }

    public Long getId() {
        return id;
    }
    public String getCredentialHash() {
        return credentialHash;
    }
    public boolean isUsed() {
        return used;
    }
    public boolean isRevoked() {
        return revoked;
    }
    public boolean isNotExpired() {
        return notExpired;
    }
}
