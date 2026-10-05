package cn.hnust.selection.request;

import javax.validation.constraints.Positive;

public class SubmitSupplementApplicationRequest {
    @Positive
    private Long teacherId;
    public Long getTeacherId() { return teacherId; }
    public void setTeacherId(Long teacherId) { this.teacherId = teacherId; }
}
