package cn.hnust.selection.request;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

public class ReviewIdentityCorrectionRequest {
    @NotBlank
    private String decision;
    @Size(max = 2000)
    private String handlingComment;

    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public String getHandlingComment() { return handlingComment; }
    public void setHandlingComment(String handlingComment) { this.handlingComment = handlingComment; }
}
