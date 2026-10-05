package cn.hnust.selection.vo;

/** 资格管理和导入页使用的学年目录视图。 */
public class AcademicYearOptionVO {
    private final Long id;
    private final String yearCode;
    private final String displayName;
    public AcademicYearOptionVO(Long id, String yearCode, String displayName) {
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
