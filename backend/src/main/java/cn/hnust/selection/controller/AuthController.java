package cn.hnust.selection.controller;

import cn.hnust.selection.common.Result;
import cn.hnust.selection.request.LoginRequest;
import cn.hnust.selection.request.PasswordChangeRequest;
import cn.hnust.selection.vo.AuthSessionVO;
import cn.hnust.selection.vo.AuthUserVO;
import cn.hnust.selection.vo.PasswordChangeVO;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.AccountAuthService;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Cookie;
import javax.validation.Valid;

/**
 * 登录、登出、当前用户概要和改密 API。
 *
 * <p>Controller 负责校验 HTTP 请求、调用认证服务，并把服务端认证主体投影成响应 DTO；
 * 账号查找、凭证判断和改密事务都由 Service 处理。角色、accountId 和授权范围始终从
 * Spring Security 主体中读取，不信任请求参数声明的身份。</p>
 *
 * <p>认证采用服务端 Session。Session 标识通过 HttpOnly Cookie 传输，不放在 JSON 响应中；
 * 登录和改密成功后会轮换 Session ID 及 CSRF 令牌。</p>
 */
@RestController
@RequestMapping("/api/auth")
@Validated
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final AccountAuthService accountAuthService;
    private final CookieCsrfTokenRepository csrfTokenRepository;
    private final boolean secureSessionCookie;

    public AuthController(AuthenticationManager authenticationManager,
                          AccountAuthService accountAuthService,
                          CookieCsrfTokenRepository csrfTokenRepository,
                          @Value("${server.servlet.session.cookie.secure:true}") boolean secureSessionCookie) {
        this.authenticationManager = authenticationManager;
        this.accountAuthService = accountAuthService;
        this.csrfTokenRepository = csrfTokenRepository;
        this.secureSessionCookie = secureSessionCookie;
    }

    /**
     * 校验登录凭证并建立已认证 Session。
     *
     * <p>先交给 AuthenticationManager 执行账号与密码验证；成功后创建/轮换 Session ID，
     * 写入新的 SecurityContext，再轮换 CSRF Cookie。响应只返回身份概要，不返回 Session 标识。</p>
     */
    @PostMapping("/login")
    public Result<AuthSessionVO> login(@Valid @RequestBody LoginRequest request,
                                             HttpServletRequest servletRequest,
                                             HttpServletResponse servletResponse) {
        Authentication authentication = authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(request.getLoginIdentifier(), request.getPassword()));
        // AuthenticationManager 成功返回后才会执行到这里。
        // 创建 Session 并更换其 ID，防止攻击者预先指定匿名 Session ID 后诱使用户登录（Session 固定攻击）。
        servletRequest.getSession(true);
        servletRequest.changeSessionId();
        // 用已验证 Authentication 建立本次请求的安全上下文，Spring Security 会将它保存进 Session。
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        rotateCsrfToken(servletRequest, servletResponse);
        AccountPrincipal principal = (AccountPrincipal) authentication.getPrincipal();
        return Result.success(new AuthSessionVO(AuthUserVO.from(principal)));
    }

    /**
     * 使当前服务端 Session 失效，并清理浏览器保存的 Session 与 CSRF Cookie。
     * 该端点只在已认证且通过 CSRF 校验后调用。
     */
    @PostMapping("/logout")
    public Result<Boolean> logout(HttpServletRequest request, HttpServletResponse response,
                                  Authentication authentication) {
        // 服务端 Session 失效并清理浏览器持有的会话/CSRF Cookie。
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        clearSessionCookie(request, response);
        csrfTokenRepository.saveToken(null, request, response);
        return Result.success(Boolean.TRUE);
    }

    /**
     * 返回由请求过滤器刷新过的当前主体概要。
     * 前端用它恢复页面登录状态；响应不包含密码哈希、临时凭证明文或授权审计信息。
     */
    @GetMapping("/me")
    public Result<AuthUserVO> me(@org.springframework.security.core.annotation.AuthenticationPrincipal AccountPrincipal principal) {
        return Result.success(AuthUserVO.from(principal));
    }

    /**
     * 校验当前密码或一次性临时凭证，设置新密码，并让当前会话继续有效。
     * Service 完成数据库事务；Controller 随后把当前请求的主体替换为改密后的新主体。
     */
    @PostMapping("/password-change")
    public Result<PasswordChangeVO> changePassword(@Valid @RequestBody PasswordChangeRequest request,
                                                         HttpServletRequest servletRequest,
                                                         HttpServletResponse servletResponse) {
        Authentication currentAuthentication = SecurityContextHolder.getContext().getAuthentication();
        AccountPrincipal currentPrincipal = (AccountPrincipal) currentAuthentication.getPrincipal();
        // Service 在同一个数据库事务里验证凭证、消费临时凭证（首次改密时）并更新密码哈希。
        // 传入的是过滤器刚从数据库刷新过的主体，不能从请求体接收 accountId。
        AccountPrincipal updatedPrincipal = accountAuthService.changePassword(currentPrincipal,
            request.getCurrentCredential(), request.getNewPassword());
        Authentication updatedAuthentication = new UsernamePasswordAuthenticationToken(
            updatedPrincipal, null, updatedPrincipal.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(updatedAuthentication);
        SecurityContextHolder.setContext(context);
        // 把当前请求切换到新主体，使本次改密后仍保持登录并反映新的 mustChangePassword 状态。
        // 轮换 Session ID 和 CSRF 令牌；旧设备上的会话会因数据库 row_version 变化在下次请求时失效。
        servletRequest.changeSessionId();
        rotateCsrfToken(servletRequest, servletResponse);
        return Result.success(new PasswordChangeVO(true, updatedPrincipal.isMustChangePassword()));
    }

    private void rotateCsrfToken(HttpServletRequest request, HttpServletResponse response) {
        // 先删除浏览器当前 CSRF Cookie，再生成并保存新值，避免身份状态改变后继续复用旧令牌。
        // CookieCsrfTokenRepository 会将新值写入 XSRF-TOKEN Cookie；前端写请求需回传到请求头。
        csrfTokenRepository.saveToken(null, request, response);
        CsrfToken token = csrfTokenRepository.generateToken(request);
        csrfTokenRepository.saveToken(token, request, response);
    }

    private void clearSessionCookie(HttpServletRequest request, HttpServletResponse response) {
        // 过期 Cookie 必须与原 Cookie 使用相同名称和 Path，浏览器才会删除正确的那一项。
        // Path 同时兼容应用部署在根路径和带 contextPath 的情况。
        Cookie cookie = new Cookie("HNUSTSESSION", "");
        String contextPath = request.getContextPath();
        cookie.setPath(contextPath == null || contextPath.isEmpty() ? "/" : contextPath + "/");
        cookie.setHttpOnly(true);
        cookie.setSecure(secureSessionCookie);
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }
}
