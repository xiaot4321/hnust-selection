package cn.hnust.selection.vo;

public class StudentPreferenceWithdrawalVO {
    private String preferenceStatus;
    public StudentPreferenceWithdrawalVO() { }
    public StudentPreferenceWithdrawalVO(String preferenceStatus) { this.preferenceStatus = preferenceStatus; }
    public String getPreferenceStatus() { return preferenceStatus; }
    public void setPreferenceStatus(String preferenceStatus) { this.preferenceStatus = preferenceStatus; }
}
