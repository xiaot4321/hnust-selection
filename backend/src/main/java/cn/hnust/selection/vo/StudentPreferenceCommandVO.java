package cn.hnust.selection.vo;

public class StudentPreferenceCommandVO {
    private Long submissionId;
    private Integer versionNo;
    private String preferenceStatus;
    private String submittedAt;
    public StudentPreferenceCommandVO() { }
    public StudentPreferenceCommandVO(Long submissionId, Integer versionNo, String preferenceStatus, String submittedAt) {
        this.submissionId = submissionId; this.versionNo = versionNo;
        this.preferenceStatus = preferenceStatus; this.submittedAt = submittedAt;
    }
    public Long getSubmissionId() { return submissionId; }
    public void setSubmissionId(Long submissionId) { this.submissionId = submissionId; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public String getPreferenceStatus() { return preferenceStatus; }
    public void setPreferenceStatus(String preferenceStatus) { this.preferenceStatus = preferenceStatus; }
    public String getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(String submittedAt) { this.submittedAt = submittedAt; }
}
