package cn.hnust.selection.request;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import javax.validation.constraints.Size;

/**
 * 创建互选批次草稿。
 *
 * <p>请求只提供批次所属学院、学年、业务编号、名称以及补选计划；状态、发布/启动时间、操作者和行版本
 * 全由服务端生成。Idempotency-Key 通过请求头传递，不在此 DTO 中重复定义。</p>
 */
public class CreateSelectionBatchRequest {
    /** 新批次真实所属学院，必须处于启用状态且在管理员授权范围内。 */
    @NotNull @Positive private Long collegeId;
    /** 批次对应的学年目录主键；服务端会锁定该学年行后校验追加批次规则。 */
    @NotNull @Positive private Long academicYearId;
    /** 学院/学年范围内唯一的批次业务编号。 */
    @NotBlank @Size(max = 48) private String batchCode;
    /** 面向用户展示的批次名称。 */
    @NotBlank @Size(max = 128) private String name;
    /** 同学院同学年已有批次时，新建追加批次必须填写原因。 */
    @Size(max = 4000) private String appendReason;
    /** 是否在发布前计划独立补选窗口；为 true 时排期必须包含 SUPPLEMENT 阶段。 */
    private boolean supplementPlanned;

    public Long getCollegeId() { return collegeId; }
    public void setCollegeId(Long collegeId) { this.collegeId = collegeId; }
    public Long getAcademicYearId() { return academicYearId; }
    public void setAcademicYearId(Long academicYearId) { this.academicYearId = academicYearId; }
    public String getBatchCode() { return batchCode; }
    public void setBatchCode(String batchCode) { this.batchCode = batchCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAppendReason() { return appendReason; }
    public void setAppendReason(String appendReason) { this.appendReason = appendReason; }
    public boolean isSupplementPlanned() { return supplementPlanned; }
    public void setSupplementPlanned(boolean supplementPlanned) { this.supplementPlanned = supplementPlanned; }
}
