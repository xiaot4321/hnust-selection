package cn.hnust.selection.request;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;

/**
 * 授予一个管理员业务能力的 HTTP 请求。
 *
 * <p>操作者、目标角色、授权时间和授权状态都不接受客户端指定；操作者从认证主体读取，
 * 目标角色和时间由服务端校验/生成。学院范围必须显式提供，批次范围可以省略表示学院级范围。</p>
 */
public class GrantAdminAuthorizationRequest {
    /** 受服务端能力目录约束的业务能力代码，例如 BATCH_MANAGER 或 BATCH_AUDIT。 */
    @NotBlank
    private String capabilityCode;

    /** 必填学院范围；Service 会核验总管理员在该学院拥有账户管理能力。 */
    @NotNull
    @Positive
    private Long collegeId;

    /** 可选批次范围；不为空时必须属于上面的学院。 */
    @Positive
    private Long batchId;

    /** 授权依据；写入授权历史并关联本次操作审计。 */
    @NotBlank
    private String basis;

    public String getCapabilityCode() { return capabilityCode; }
    public void setCapabilityCode(String capabilityCode) { this.capabilityCode = capabilityCode; }
    public Long getCollegeId() { return collegeId; }
    public void setCollegeId(Long collegeId) { this.collegeId = collegeId; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getBasis() { return basis; }
    public void setBasis(String basis) { this.basis = basis; }
}
