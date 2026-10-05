package cn.hnust.selection.entity;

/**
 * 学年目录查询映射。
 *
 * <p>{@code yearCode} 是跨学年业务所用的稳定代码，{@code displayName} 供管理员在页面选择目标年度；
 * 业务规则和学年状态判断不放在该数据对象中。</p>
 */
public class AcademicYearEntity {
    private final Long id;
    private final String yearCode;
    private final String displayName;

    public AcademicYearEntity(Long id, String yearCode, String displayName) {
        this.id = id;
        this.yearCode = yearCode;
        this.displayName = displayName;
    }

    public Long getId() {
        return id;
    }
    public String getYearCode() {
        return yearCode;
    }
    public String getDisplayName() {
        return displayName;
    }
}
