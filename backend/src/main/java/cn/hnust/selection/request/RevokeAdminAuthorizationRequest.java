package cn.hnust.selection.request;

import javax.validation.constraints.NotBlank;

/**
 * 撤销一项管理员业务能力时提交的原因。
 *
 * <p>目标授权记录、操作者和撤销时间由服务端从路径、认证主体及数据库时间确定，
 * 因此请求体只包含业务方需要填写的撤销理由。</p>
 */
public class RevokeAdminAuthorizationRequest {
    /** 撤销原因必填；会写入不可覆盖的审计记录。 */
    @NotBlank
    private String reason;

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
