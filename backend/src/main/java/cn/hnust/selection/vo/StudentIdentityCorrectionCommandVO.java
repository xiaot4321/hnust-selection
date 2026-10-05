package cn.hnust.selection.vo;

/** 身份更正申请创建/幂等重放的最小回执。 */
public class StudentIdentityCorrectionCommandVO {
    private Long requestId;
    private String status;
    private String submittedAt;

    public StudentIdentityCorrectionCommandVO() { }
    public StudentIdentityCorrectionCommandVO(Long requestId, String status, String submittedAt) {
        this.requestId = requestId;
        this.status = status;
        this.submittedAt = submittedAt;
    }
    public Long getRequestId() { return requestId; }
    public void setRequestId(Long requestId) { this.requestId = requestId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(String submittedAt) { this.submittedAt = submittedAt; }
}
