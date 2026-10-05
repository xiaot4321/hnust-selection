package cn.hnust.selection.request;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/** 常规轮次重开命令；新截止时间须携带 UTC 偏移或 Z。 */
public class ReopenRoundRequest {
    @NotBlank private String newEndAt;
    @NotBlank @Size(max = 4000) private String reason;

    public String getNewEndAt() { return newEndAt; }
    public void setNewEndAt(String newEndAt) { this.newEndAt = newEndAt; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
