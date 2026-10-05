package cn.hnust.selection.entity;

/**
 * 学院表的只读映射。
 *
 * <p>管理模块用学院主键执行授权范围校验，页面目录则使用代码和名称展示。是否启用也从数据库映射，
 * 供首次初始化等需要核对主数据状态的流程判断。</p>
 */
public class CollegeEntity {
    private final Long id;
    private final String code;
    private final String name;
    private final boolean active;

    public CollegeEntity(Long id, String code, String name, boolean active) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.active = active;
    }

    public Long getId() {
        return id;
    }
    public String getCode() {
        return code;
    }
    public String getName() {
        return name;
    }
    public boolean isActive() {
        return active;
    }
}
