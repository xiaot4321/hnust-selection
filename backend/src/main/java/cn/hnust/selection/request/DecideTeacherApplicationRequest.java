package cn.hnust.selection.request;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;

public class DecideTeacherApplicationRequest {
    @NotNull
    @Pattern(regexp = "ADMIT|NOT_ADMITTED")
    private String decision;
    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
}
