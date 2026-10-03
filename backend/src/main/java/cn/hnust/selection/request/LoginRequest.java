package cn.hnust.selection.request;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 登录请求的 HTTP 输入模型。
 * 只接受用户可证明的登录标识与凭证；角色、账号主键、人员身份、学院及授权范围只能由服务端查询得出。
 */
public class LoginRequest {
    /** 学号、工号或系统分配的管理员登录标识；不接受空白值，长度上限防止异常大请求。 */
    @NotBlank
    @Size(max = 128)
    private String loginIdentifier;

    /** 用户当前输入的正式密码或有效临时凭证；仅在认证请求处理中使用，不回显到响应。 */
    @NotBlank
    @Size(max = 128)
    private String password;

    public String getLoginIdentifier() { return loginIdentifier; }
    public void setLoginIdentifier(String loginIdentifier) { this.loginIdentifier = loginIdentifier; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
