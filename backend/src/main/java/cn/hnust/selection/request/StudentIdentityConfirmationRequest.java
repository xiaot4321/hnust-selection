package cn.hnust.selection.request;

import javax.validation.constraints.Positive;

public class StudentIdentityConfirmationRequest {
    @Positive
    private Integer classificationVersion;
    public Integer getClassificationVersion() { return classificationVersion; }
    public void setClassificationVersion(Integer classificationVersion) { this.classificationVersion = classificationVersion; }
}
