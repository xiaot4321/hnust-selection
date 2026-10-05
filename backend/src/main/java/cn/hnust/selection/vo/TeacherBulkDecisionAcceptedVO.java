package cn.hnust.selection.vo;

public class TeacherBulkDecisionAcceptedVO {
    private Long operationId;
    private String status;
    public TeacherBulkDecisionAcceptedVO() { }
    public TeacherBulkDecisionAcceptedVO(Long operationId, String status) { this.operationId = operationId; this.status = status; }
    public Long getOperationId() { return operationId; }
    public void setOperationId(Long operationId) { this.operationId = operationId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
