package cn.hnust.selection.request;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 可编辑批次元数据。
 *
 * <p>该请求只在 DRAFT 状态生效；行版本不放在请求体中，而由 Controller 从强 If-Match 标签解析，
 * 避免客户端提交的版本字段与 HTTP 条件头发生歧义。</p>
 */
public class UpdateSelectionBatchRequest {
    /** 面向用户展示的批次名称。 */
    @NotBlank @Size(max = 128) private String name;
    /** 追加批次创建原因；普通批次可为空。 */
    @Size(max = 4000) private String appendReason;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAppendReason() { return appendReason; }
    public void setAppendReason(String appendReason) { this.appendReason = appendReason; }
}
