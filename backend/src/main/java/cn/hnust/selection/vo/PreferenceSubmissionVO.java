package cn.hnust.selection.vo;

import java.util.ArrayList;
import java.util.List;

public class PreferenceSubmissionVO {
    private Long submissionId;
    private Integer versionNo;
    private String status;
    private String submittedAt;
    private String lockedAt;
    private List<StudentPreferenceItemVO> items = new ArrayList<StudentPreferenceItemVO>();

    public Long getSubmissionId() { return submissionId; }
    public void setSubmissionId(Long submissionId) { this.submissionId = submissionId; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(String submittedAt) { this.submittedAt = submittedAt; }
    public String getLockedAt() { return lockedAt; }
    public void setLockedAt(String lockedAt) { this.lockedAt = lockedAt; }
    public List<StudentPreferenceItemVO> getItems() { return items; }
    public void setItems(List<StudentPreferenceItemVO> items) { this.items = items; }
}
