package cn.hnust.selection.vo;

/** 专业目录的最小展示字段。 */
public class MajorOptionVO {
    private Long majorId;
    private String majorCode;
    private String majorName;
    private boolean active;

    public MajorOptionVO() { }
    public MajorOptionVO(Long majorId, String majorCode, String majorName, boolean active) {
        this.majorId = majorId;
        this.majorCode = majorCode;
        this.majorName = majorName;
        this.active = active;
    }
    public Long getMajorId() { return majorId; }
    public void setMajorId(Long majorId) { this.majorId = majorId; }
    public String getMajorCode() { return majorCode; }
    public void setMajorCode(String majorCode) { this.majorCode = majorCode; }
    public String getMajorName() { return majorName; }
    public void setMajorName(String majorName) { this.majorName = majorName; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
