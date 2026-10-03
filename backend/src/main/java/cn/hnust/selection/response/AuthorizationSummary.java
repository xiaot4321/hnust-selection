package cn.hnust.selection.response;

/**
 * /auth/me 返回的一条管理员授权摘要。
 * batchId 为空表示该能力在所属学院内不限定批次；不返回授权依据、签发人或撤销历史。
 */
public class AuthorizationSummary {
    private final String capabilityCode;
    private final Long collegeId;
    private final Long batchId;

    public AuthorizationSummary(String capabilityCode, Long collegeId, Long batchId) {
        this.capabilityCode = capabilityCode;
        this.collegeId = collegeId;
        this.batchId = batchId;
    }

    public String getCapabilityCode() { return capabilityCode; }
    public Long getCollegeId() { return collegeId; }
    public Long getBatchId() { return batchId; }
}
