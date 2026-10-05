package cn.hnust.selection.vo;

/** 学生本人身份更正申请记录；不包含处理人账号或内部审计字段。 */
public class StudentIdentityCorrectionVO {
    private Long requestId;
    private String submittedAt;
    private Integer currentClassificationVersion;
    private StudentProfileVO.StudentMajorVO requestedMajor;
    private String requestedDegreeType;
    private String studentExplanation;
    private String status;
    private String handledAt;
    private String handlingComment;
    private Integer resultingClassificationVersion;

    public Long getRequestId() { return requestId; }
    public void setRequestId(Long requestId) { this.requestId = requestId; }
    public String getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(String submittedAt) { this.submittedAt = submittedAt; }
    public Integer getCurrentClassificationVersion() { return currentClassificationVersion; }
    public void setCurrentClassificationVersion(Integer currentClassificationVersion) { this.currentClassificationVersion = currentClassificationVersion; }
    public StudentProfileVO.StudentMajorVO getRequestedMajor() { return requestedMajor; }
    public void setRequestedMajor(StudentProfileVO.StudentMajorVO requestedMajor) { this.requestedMajor = requestedMajor; }
    public String getRequestedDegreeType() { return requestedDegreeType; }
    public void setRequestedDegreeType(String requestedDegreeType) { this.requestedDegreeType = requestedDegreeType; }
    public String getStudentExplanation() { return studentExplanation; }
    public void setStudentExplanation(String studentExplanation) { this.studentExplanation = studentExplanation; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getHandledAt() { return handledAt; }
    public void setHandledAt(String handledAt) { this.handledAt = handledAt; }
    public String getHandlingComment() { return handlingComment; }
    public void setHandlingComment(String handlingComment) { this.handlingComment = handlingComment; }
    public Integer getResultingClassificationVersion() { return resultingClassificationVersion; }
    public void setResultingClassificationVersion(Integer resultingClassificationVersion) { this.resultingClassificationVersion = resultingClassificationVersion; }
}
