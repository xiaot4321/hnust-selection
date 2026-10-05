package cn.hnust.selection.vo;

/** 管理员在获授权学院内审核身份纠错申请所需的最小字段。 */
public class AdminIdentityCorrectionVO {
    private Long requestId;
    private Long studentId;
    private String studentNo;
    private String fullName;
    private Long collegeId;
    private String collegeName;
    private String submittedAt;
    private Integer currentClassificationVersion;
    private Long currentMajorId;
    private String currentMajorCode;
    private String currentMajorName;
    private String currentDegreeType;
    private Long requestedMajorId;
    private String requestedMajorCode;
    private String requestedMajorName;
    private String requestedDegreeType;
    private String studentExplanation;
    private String status;
    private String handlingComment;

    public Long getRequestId() { return requestId; }
    public void setRequestId(Long value) { requestId = value; }
    public Long getStudentId() { return studentId; }
    public void setStudentId(Long value) { studentId = value; }
    public String getStudentNo() { return studentNo; }
    public void setStudentNo(String value) { studentNo = value; }
    public String getFullName() { return fullName; }
    public void setFullName(String value) { fullName = value; }
    public Long getCollegeId() { return collegeId; }
    public void setCollegeId(Long value) { collegeId = value; }
    public String getCollegeName() { return collegeName; }
    public void setCollegeName(String value) { collegeName = value; }
    public String getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(String value) { submittedAt = value; }
    public Integer getCurrentClassificationVersion() { return currentClassificationVersion; }
    public void setCurrentClassificationVersion(Integer value) { currentClassificationVersion = value; }
    public Long getCurrentMajorId() { return currentMajorId; }
    public void setCurrentMajorId(Long value) { currentMajorId = value; }
    public String getCurrentMajorCode() { return currentMajorCode; }
    public void setCurrentMajorCode(String value) { currentMajorCode = value; }
    public String getCurrentMajorName() { return currentMajorName; }
    public void setCurrentMajorName(String value) { currentMajorName = value; }
    public String getCurrentDegreeType() { return currentDegreeType; }
    public void setCurrentDegreeType(String value) { currentDegreeType = value; }
    public Long getRequestedMajorId() { return requestedMajorId; }
    public void setRequestedMajorId(Long value) { requestedMajorId = value; }
    public String getRequestedMajorCode() { return requestedMajorCode; }
    public void setRequestedMajorCode(String value) { requestedMajorCode = value; }
    public String getRequestedMajorName() { return requestedMajorName; }
    public void setRequestedMajorName(String value) { requestedMajorName = value; }
    public String getRequestedDegreeType() { return requestedDegreeType; }
    public void setRequestedDegreeType(String value) { requestedDegreeType = value; }
    public String getStudentExplanation() { return studentExplanation; }
    public void setStudentExplanation(String value) { studentExplanation = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getHandlingComment() { return handlingComment; }
    public void setHandlingComment(String value) { handlingComment = value; }
}
