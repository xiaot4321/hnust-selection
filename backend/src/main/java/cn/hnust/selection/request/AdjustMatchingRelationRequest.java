package cn.hnust.selection.request;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

public class AdjustMatchingRelationRequest {
    @NotBlank @Size(max = 16) private String adjustmentType;
    private Long newTeacherId;
    @NotBlank @Size(max = 4000) private String reason;
    @Size(max = 2000) private String approvalComment;
    public String getAdjustmentType() { return adjustmentType; }
    public void setAdjustmentType(String adjustmentType) { this.adjustmentType = adjustmentType; }
    public Long getNewTeacherId() { return newTeacherId; }
    public void setNewTeacherId(Long newTeacherId) { this.newTeacherId = newTeacherId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getApprovalComment() { return approvalComment; }
    public void setApprovalComment(String approvalComment) { this.approvalComment = approvalComment; }
}
