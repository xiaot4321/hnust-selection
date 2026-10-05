package cn.hnust.selection.entity;

import java.sql.Date;

/**
 * 专业目录的数据库查询投影。
 *
 * <p>专业代码在学院范围内识别目录项；停用和有效区间用于控制新建档案及页面展示；
 * {@code rowVersion} 支持更新时检查客户端读取后的并发修改。API 日期格式由 Service 负责转换。</p>
 */
public class MajorEntity {
    private final Long id;
    private final Long collegeId;
    private final String majorCode;
    private final String name;
    private final boolean active;
    private final Date validFrom;
    private final Date validTo;
    private final long rowVersion;

    public MajorEntity(Long id, Long collegeId, String majorCode, String name, boolean active,
                       Date validFrom, Date validTo, long rowVersion) {
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
    public Date getValidFrom() {
        return validFrom;
    }
    public Date getValidTo() {
        return validTo;
    }
    public long getRowVersion() {
        return rowVersion;
    }
}
