package cn.hnust.selection.vo;

/** 导师当前业务申请列表项；字段严格限于申请快照允许公开的资料。 */
public class TeacherApplicationVO {
    private Long applicationId;
    private Long batchId;
    private String batchName;
    private Integer roundNo;
    private Integer preferenceOrder;
    private String studentNo;
    private String fullName;
    private String majorName;
    private String degreeType;
    private String biography;
    private Long resumeFileId;
    private String status;
    private String enteredReviewAt;
    private String processedAt;

    public Long getApplicationId() { return applicationId; }
    public void setApplicationId(Long applicationId) { this.applicationId = applicationId; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getBatchName() { return batchName; }
    public void setBatchName(String batchName) { this.batchName = batchName; }
    public Integer getRoundNo() { return roundNo; }
    public void setRoundNo(Integer roundNo) { this.roundNo = roundNo; }
    public Integer getPreferenceOrder() { return preferenceOrder; }
    public void setPreferenceOrder(Integer preferenceOrder) { this.preferenceOrder = preferenceOrder; }
    public String getStudentNo() { return studentNo; }
    public void setStudentNo(String studentNo) { this.studentNo = studentNo; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getMajorName() { return majorName; }
    public void setMajorName(String majorName) { this.majorName = majorName; }
    public String getDegreeType() { return degreeType; }
    public void setDegreeType(String degreeType) { this.degreeType = degreeType; }
    public String getBiography() { return biography; }
    public void setBiography(String biography) { this.biography = biography; }
    public Long getResumeFileId() { return resumeFileId; }
    public void setResumeFileId(Long resumeFileId) { this.resumeFileId = resumeFileId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getEnteredReviewAt() { return enteredReviewAt; }
    public void setEnteredReviewAt(String enteredReviewAt) { this.enteredReviewAt = enteredReviewAt; }
    public String getProcessedAt() { return processedAt; }
    public void setProcessedAt(String processedAt) { this.processedAt = processedAt; }
}
