package cn.hnust.selection.request;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 改密请求的 HTTP 输入模型。
 * 首次登录时 currentCredential 是管理员发放的临时凭证；普通改密时它是当前正式密码。
 * 当前账号从已认证主体中取得，因此请求不接受 accountId，防止客户端替其他账号改密。
 */
public class PasswordChangeRequest {
    /** 用于证明当前操作者持有账号的有效凭证，具体验证类型由当前主体的改密状态决定。 */
    @NotBlank
    @Size(max = 128)
    private String currentCredential;

    @NotBlank
    @Size(max = 128)
    private String newPassword;

    public String getCurrentCredential() { return currentCredential; }
    public void setCurrentCredential(String currentCredential) { this.currentCredential = currentCredential; }
    public String getNewPassword() { return newPassword; }
    public void setNewPassword(String newPassword) { this.newPassword = newPassword; }
}
    /** 新的正式密码明文；服务端校验长度后只写入 BCrypt 哈希。 */
