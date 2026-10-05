package cn.hnust.selection.vo;

public class StudentIdentityConfirmationVO {
    private Integer confirmedClassificationVersion;
    private String confirmedAt;
    public StudentIdentityConfirmationVO() { }
    public StudentIdentityConfirmationVO(Integer confirmedClassificationVersion, String confirmedAt) {
        this.confirmedClassificationVersion = confirmedClassificationVersion;
        this.confirmedAt = confirmedAt;
    }
    public Integer getConfirmedClassificationVersion() { return confirmedClassificationVersion; }
    public void setConfirmedClassificationVersion(Integer confirmedClassificationVersion) { this.confirmedClassificationVersion = confirmedClassificationVersion; }
    public String getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(String confirmedAt) { this.confirmedAt = confirmedAt; }
}
