package cn.hnust.selection.vo;

public class AllowedMajorVO {
    private Long majorId;
    private String majorCode;
    private String majorName;

    public AllowedMajorVO() { }
    public AllowedMajorVO(Long majorId, String majorCode, String majorName) {
        this.majorId = majorId;
        this.majorCode = majorCode;
        this.majorName = majorName;
    }
    public Long getMajorId() { return majorId; }
    public void setMajorId(Long majorId) { this.majorId = majorId; }
    public String getMajorCode() { return majorCode; }
    public void setMajorCode(String majorCode) { this.majorCode = majorCode; }
    public String getMajorName() { return majorName; }
    public void setMajorName(String majorName) { this.majorName = majorName; }
}
