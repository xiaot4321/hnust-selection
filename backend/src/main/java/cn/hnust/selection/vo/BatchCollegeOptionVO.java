package cn.hnust.selection.vo;

/**
 * 批次管理可访问学院选项。
 *
 * <p>{@code canCreateBatch} 只在存在学院级 BATCH_MANAGER 授权或总管理员身份时为 true；
 * 仅被授予单个批次的管理员仍可在此选择学院查看那个批次，但不能借学院目录创建新批次。</p>
 */
public class BatchCollegeOptionVO {
    private final Long id;
    private final String code;
    private final String name;
    private final boolean canCreateBatch;

    public BatchCollegeOptionVO(Long id, String code, String name, boolean canCreateBatch) {
        this.id = id; this.code = code; this.name = name; this.canCreateBatch = canCreateBatch;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public boolean isCanCreateBatch() { return canCreateBatch; }
}
