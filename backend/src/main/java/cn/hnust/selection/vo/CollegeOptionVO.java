package cn.hnust.selection.vo;

/** 前端学院选择器的展示项；记录仅来自当前调用者获准访问的学院范围。 */
public class CollegeOptionVO {
    private final Long id;
    private final String code;
    private final String name;
    public CollegeOptionVO(Long id, String code, String name) { this.id = id;
        this.code = code;
        this.name = name; }
    public Long getId() {
        return id;
    }
    public String getCode() {
        return code;
    }
    public String getName() {
        return name;
    }
}
