package cn.hnust.selection.request;

import javax.validation.constraints.Size;

/** 导师公开资料的完整编辑值；不接受姓名、工号、审核状态或发布状态。 */
public class UpdateTeacherProfileRequest {
    @Size(max = 2000)
    private String researchDirections;
    @Size(max = 10000)
    private String biography;
    public String getResearchDirections() { return researchDirections; }
    public void setResearchDirections(String researchDirections) { this.researchDirections = researchDirections; }
    public String getBiography() { return biography; }
    public void setBiography(String biography) { this.biography = biography; }
}
