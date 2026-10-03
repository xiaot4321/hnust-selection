package cn.hnust.selection.response;

/**
 * 总管理员查询授权记录时返回的安全投影。
 *
 * <p>该响应包含授权范围和审计主体，供总管理员核对授权沿革；不会返回目标账号密码、
 * 临时凭证或其他人员资料。时间按 ISO-8601 UTC 字符串返回。</p>
 */
public class AdminAccountAuthorizationResponse {
    private final Long authorizationId;
    private final String capabilityCode;
    private final Long collegeId;
    private final Long batchId;
    private final String basis;
    private final Long grantedBy;
    private final String grantedAt;
    private final String status;
    private final Long revokedBy;
    private final String revokedAt;

    public AdminAccountAuthorizationResponse(Long authorizationId, String capabilityCode,
                                             Long collegeId, Long batchId, String basis,
                                             Long grantedBy, String grantedAt, String status,
                                             Long revokedBy, String revokedAt) {
        this.authorizationId = authorizationId;
        this.capabilityCode = capabilityCode;
        this.collegeId = collegeId;
        this.batchId = batchId;
        this.basis = basis;
        this.grantedBy = grantedBy;
        this.grantedAt = grantedAt;
        this.status = status;
        this.revokedBy = revokedBy;
        this.revokedAt = revokedAt;
    }

    public Long getAuthorizationId() { return authorizationId; }
    public String getCapabilityCode() { return capabilityCode; }
    public Long getCollegeId() { return collegeId; }
    public Long getBatchId() { return batchId; }
    public String getBasis() { return basis; }
    public Long getGrantedBy() { return grantedBy; }
    public String getGrantedAt() { return grantedAt; }
    public String getStatus() { return status; }
    public Long getRevokedBy() { return revokedBy; }
    public String getRevokedAt() { return revokedAt; }
}
