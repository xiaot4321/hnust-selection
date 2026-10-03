package cn.hnust.selection.response;

/**
 * 登录成功后的 JSON 响应结构。
 * authenticated 表示服务端已建立认证上下文，user 是可供前端展示的身份摘要；
 * 实际 Session ID 仅通过 HttpOnly Cookie 下发，不包含在此对象中。
 */
public class AuthSessionResponse {
    private final boolean authenticated;
    private final AuthUserResponse user;

    public AuthSessionResponse(AuthUserResponse user) {
        this.authenticated = true;
        this.user = user;
    }

    public boolean isAuthenticated() { return authenticated; }
    public AuthUserResponse getUser() { return user; }
}
