package cn.hnust.selection.request;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import javax.validation.constraints.Size;

/** 新增学院专业目录项；专业代码在同学院范围内唯一，创建后通过停用而非删除保留历史。 */
public class CreateMajorRequest {
    @NotNull @Positive private Long collegeId;
    @NotBlank @Size(max = 32) private String majorCode;
    @NotBlank @Size(max = 128) private String name;
    @Size(max = 10) private String validFrom;
    @Size(max = 10) private String validTo;
    @NotBlank @Size(max = 2000) private String changeBasis;

    public Long getCollegeId() { return collegeId; }
    public void setCollegeId(Long collegeId) { this.collegeId = collegeId; }
    public String getMajorCode() { return majorCode; }
    public void setMajorCode(String majorCode) { this.majorCode = majorCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getValidFrom() { return validFrom; }
    public void setValidFrom(String validFrom) { this.validFrom = validFrom; }
    public String getValidTo() { return validTo; }
    public void setValidTo(String validTo) { this.validTo = validTo; }
    public String getChangeBasis() { return changeBasis; }
    public void setChangeBasis(String changeBasis) { this.changeBasis = changeBasis; }
}
