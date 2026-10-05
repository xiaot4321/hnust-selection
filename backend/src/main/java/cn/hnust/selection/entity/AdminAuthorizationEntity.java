package cn.hnust.selection.entity;

import java.sql.Timestamp;

/**
 * 管理员授权记录的数据库映射对象。
 *
 * <p>{@code accountId}、{@code collegeId} 和可空的 {@code batchId} 确定被授权管理员与业务范围；
 * {@code capabilityCode} 表示获授能力，{@code authoritySlot} 表示唯一保留能力槽位；
 * {@code basis}、授予/撤销操作者与时间以及 {@code revocationReason} 用于展示和核对授权沿革。
 * 撤销状态由 {@code revokedAt} 推导，而不是在数据库中重复存储。</p>
 *
 * <p>该对象仅供后端内部使用；对外 API 应转换为 {@code vo} 包中的视图对象，避免持久化结构泄漏。</p>
 */
public class AdminAuthorizationEntity {
    private final Long id;
    private final Long accountId;
    private final Long collegeId;
    private final Long batchId;
    private final String capabilityCode;
    private final String authoritySlot;
    private final String basis;
    private final Long grantedBy;
    private final Timestamp grantedAt;
    private final Long revokedBy;
    private final Timestamp revokedAt;
    private final String revocationReason;

    public AdminAuthorizationEntity(Long id, Long accountId, Long collegeId, Long batchId,
                                    String capabilityCode, String authoritySlot, String basis,
                                    Long grantedBy, Timestamp grantedAt, Long revokedBy,
                                    Timestamp revokedAt, String revocationReason) {
        this.id = id;
        this.accountId = accountId;
        this.collegeId = collegeId;
        this.batchId = batchId;
        this.capabilityCode = capabilityCode;
        this.authoritySlot = authoritySlot;
        this.basis = basis;
        this.grantedBy = grantedBy;
        this.grantedAt = copy(grantedAt);
        this.revokedBy = revokedBy;
        this.revokedAt = copy(revokedAt);
        this.revocationReason = revocationReason;
    }

    public Long getId() {
        return id;
    }
    public Long getAccountId() {
        return accountId;
    }
    public Long getCollegeId() {
        return collegeId;
    }
    public Long getBatchId() {
        return batchId;
    }
    public String getCapabilityCode() {
        return capabilityCode;
    }
    public String getAuthoritySlot() {
        return authoritySlot;
    }
    public String getBasis() {
        return basis;
    }
    public Long getGrantedBy() {
        return grantedBy;
    }
    public Timestamp getGrantedAt() {
        return copy(grantedAt);
    }
    public Long getRevokedBy() {
        return revokedBy;
    }
    public Timestamp getRevokedAt() {
        return copy(revokedAt);
    }
    public String getRevocationReason() {
        return revocationReason;
    }
    public boolean isRevoked() {
        return revokedAt != null;
    }

    private static Timestamp copy(Timestamp value) {
        return value == null ? null : new Timestamp(value.getTime());
    }
}
