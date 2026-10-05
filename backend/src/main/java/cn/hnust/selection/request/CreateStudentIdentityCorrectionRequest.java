package cn.hnust.selection.request;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Positive;

/** 学生提交身份核对申请；只记录申请意向，不直接改动学生分类。 */
public class CreateStudentIdentityCorrectionRequest {
    @Positive
    private Long requestedMajorId;

    @Pattern(regexp = "ACADEMIC_MASTER|PROFESSIONAL_MASTER")
    private String requestedDegreeType;

    @NotBlank
    private String studentExplanation;

    public Long getRequestedMajorId() { return requestedMajorId; }
    public void setRequestedMajorId(Long requestedMajorId) { this.requestedMajorId = requestedMajorId; }
    public String getRequestedDegreeType() { return requestedDegreeType; }
    public void setRequestedDegreeType(String requestedDegreeType) { this.requestedDegreeType = requestedDegreeType; }
    public String getStudentExplanation() { return studentExplanation; }
    public void setStudentExplanation(String studentExplanation) { this.studentExplanation = studentExplanation; }
}
