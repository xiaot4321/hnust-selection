package cn.hnust.selection.security;

import cn.hnust.selection.service.AccountAuthService;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

/**
 * Spring Security 的本地账号认证入口。
 *
 * <p>AuthenticationManager 将登录表单转换成 UsernamePasswordAuthenticationToken 后交给本 Provider，
 * Provider 负责规范化登录标识，并把凭证验证委托给 AccountAuthService。服务支持正式密码和首次登录
 * 临时凭证；验证成功后只保留 AccountPrincipal 和其 authority，不把提交的明文密码放进 Authentication。</p>
 */
@Component
public class AccountAuthenticationProvider implements AuthenticationProvider {
    private final AccountAuthService accountAuthService;

    public AccountAuthenticationProvider(AccountAuthService accountAuthService) {
        this.accountAuthService = accountAuthService;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        // 登录标识允许去掉首尾空格，避免复制粘贴造成无意登录失败；密码必须逐字节按用户输入校验，不能 trim。
        String loginIdentifier = authentication.getName() == null ? "" : authentication.getName().trim();
        String credential = authentication.getCredentials() == null ? "" : authentication.getCredentials().toString();
        AccountPrincipal principal = accountAuthService.authenticate(loginIdentifier, credential);
        // 第三个参数是授权集合；第二个密码参数明确置空，避免认证成功对象继续持有明文凭证。
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    @Override
    public boolean supports(Class<?> authentication) {
        // 仅处理用户名/密码 token；其他 Authentication 类型由 Spring Security 的其他 Provider 处理。
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
