package cn.hnust.selection.request;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 创建普通 ADMIN 账号的请求。
 * 登录标识由操作者提供；角色、账号状态、密码和授权范围均由服务端确定。
 */
public class CreateAdminAccountRequest {
    @NotBlank
    @Size(max = 128)
    private String loginIdentifier;

    public String getLoginIdentifier() { return loginIdentifier; }
    public void setLoginIdentifier(String loginIdentifier) { this.loginIdentifier = loginIdentifier; }
}
