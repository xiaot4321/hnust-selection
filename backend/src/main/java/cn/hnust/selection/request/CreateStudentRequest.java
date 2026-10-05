package cn.hnust.selection.request;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import javax.validation.constraints.Positive;

/**
 * 学院管理员创建学生账号时提交的白名单字段。
 *
 * <p>请求不接收角色、账号状态、密码哈希、分类版本号或操作者编号；这些字段只能由服务端决定。
 * 学生的专业/学位分类首次写入也要形成可追溯的分类修订记录。</p>
 */
public class CreateStudentRequest {
    @NotBlank @Size(max = 128) private String loginIdentifier;
    @NotBlank @Size(max = 64) private String studentNo;
    @NotBlank @Size(max = 128) private String fullName;
    @NotNull @Positive private Long collegeId;
    @NotBlank @Size(max = 32) private String majorCode;
    @NotBlank @Pattern(regexp = "ACADEMIC_MASTER|PROFESSIONAL_MASTER") private String degreeType;
    @NotBlank @Size(max = 16) private String enrollmentYearCode;
    @NotBlank @Size(max = 2000) private String classificationBasis;
    @NotBlank @Size(max = 2000) private String classificationReason;

    public String getLoginIdentifier() { return loginIdentifier; }
    public void setLoginIdentifier(String loginIdentifier) { this.loginIdentifier = loginIdentifier; }
    public String getStudentNo() { return studentNo; }
    public void setStudentNo(String studentNo) { this.studentNo = studentNo; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public Long getCollegeId() { return collegeId; }
    public void setCollegeId(Long collegeId) { this.collegeId = collegeId; }
    public String getMajorCode() { return majorCode; }
    public void setMajorCode(String majorCode) { this.majorCode = majorCode; }
    public String getDegreeType() { return degreeType; }
    public void setDegreeType(String degreeType) { this.degreeType = degreeType; }
    public String getEnrollmentYearCode() { return enrollmentYearCode; }
    public void setEnrollmentYearCode(String enrollmentYearCode) { this.enrollmentYearCode = enrollmentYearCode; }
    public String getClassificationBasis() { return classificationBasis; }
    public void setClassificationBasis(String classificationBasis) { this.classificationBasis = classificationBasis; }
    public String getClassificationReason() { return classificationReason; }
    public void setClassificationReason(String classificationReason) { this.classificationReason = classificationReason; }
}
