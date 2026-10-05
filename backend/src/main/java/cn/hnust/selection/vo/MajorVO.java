package cn.hnust.selection.vo;

/**
 * 专业目录 API 的只读视图，不直接向接口暴露持久化 Entity。
 *
 * <p>{@code majorCode} 在所属学院内标识专业；{@code active} 决定该目录项能否用于新建人员档案，
 * {@code validFrom} 和 {@code validTo} 表示目录有效区间。更新请求可提交 {@code rowVersion}，
 * 由服务端检测客户端读取后是否已有其他修改。</p>
 */
public class MajorVO {
    private final Long id;
    private final Long collegeId;
    private final String majorCode;
    private final String name;
    private final boolean active;
    private final String validFrom;
    private final String validTo;
    private final long rowVersion;

    public MajorVO(Long id, Long collegeId, String majorCode, String name, boolean active,
                         String validFrom, String validTo, long rowVersion) {
        this.id = id;
        this.collegeId = collegeId;
        this.majorCode = majorCode;
        this.name = name;
        this.active = active;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.rowVersion = rowVersion;
    }
    public Long getId() {
        return id;
    }
    public Long getCollegeId() {
        return collegeId;
    }
    public String getMajorCode() {
        return majorCode;
    }
    public String getName() {
        return name;
    }
    public boolean isActive() {
        return active;
    }
    public String getValidFrom() {
        return validFrom;
    }
    public String getValidTo() {
        return validTo;
    }
    public long getRowVersion() {
        return rowVersion;
    }
}
