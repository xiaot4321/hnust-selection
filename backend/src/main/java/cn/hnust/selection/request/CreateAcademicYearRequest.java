package cn.hnust.selection.request;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import javax.validation.constraints.Size;

/** 新增全局学年目录项；创建范围由当前管理员授权学院确定并写入审计。 */
public class CreateAcademicYearRequest {
    @NotNull @Positive private Long collegeId;
    @NotBlank @Size(max = 16) private String yearCode;
    @NotBlank @Size(max = 64) private String displayName;
    @NotBlank @Size(max = 2000) private String changeBasis;

    public Long getCollegeId() { return collegeId; }
    public void setCollegeId(Long collegeId) { this.collegeId = collegeId; }
    public String getYearCode() { return yearCode; }
    public void setYearCode(String yearCode) { this.yearCode = yearCode; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getChangeBasis() { return changeBasis; }
    public void setChangeBasis(String changeBasis) { this.changeBasis = changeBasis; }
}
