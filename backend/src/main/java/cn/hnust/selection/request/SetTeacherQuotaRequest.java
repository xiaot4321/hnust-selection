package cn.hnust.selection.request;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

/**
 * 设置导师本批次名额上限。
 *
 * <p>请求不接受已占用人数、资格依据或版本号；这些值均由服务端读取。版本号通过强 If-Match 标签传递，
 * 命令另需 Idempotency-Key 以安全处理客户端重试。</p>
 */
public class SetTeacherQuotaRequest {
    /** 包含已占用名额在内的总上限；允许为 0，但不能小于服务端读取的 occupiedCount。 */
    @NotNull @Min(0) private Integer quotaLimit;

    public Integer getQuotaLimit() { return quotaLimit; }
    public void setQuotaLimit(Integer quotaLimit) { this.quotaLimit = quotaLimit; }
}
