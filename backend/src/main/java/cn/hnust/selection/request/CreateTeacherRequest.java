package cn.hnust.selection.request;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import javax.validation.constraints.Size;

/** 导师建档的最小字段集；公开研究资料由导师登录后另行维护并走审核流程。 */
public class CreateTeacherRequest {
    @NotBlank @Size(max = 128) private String loginIdentifier;
    @NotBlank @Size(max = 64) private String employeeNo;
    @NotBlank @Size(max = 128) private String fullName;
    @NotNull @Positive private Long collegeId;

    public String getLoginIdentifier() { return loginIdentifier; }
    public void setLoginIdentifier(String loginIdentifier) { this.loginIdentifier = loginIdentifier; }
    public String getEmployeeNo() { return employeeNo; }
    public void setEmployeeNo(String employeeNo) { this.employeeNo = employeeNo; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public Long getCollegeId() { return collegeId; }
    public void setCollegeId(Long collegeId) { this.collegeId = collegeId; }
}
