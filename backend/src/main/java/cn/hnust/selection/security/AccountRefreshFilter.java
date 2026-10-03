package cn.hnust.selection.security;

import cn.hnust.selection.common.Result;
import cn.hnust.selection.service.AccountAuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 每个受保护请求进入授权判断/Controller 前刷新当前账号主体。
 *
 * <p>服务端 Session 只在登录时创建；如果长期直接使用其中保存的主体，账号停用、角色修改、密码重置
 * 或管理员授权撤销可能要等 Session 过期才生效。本过滤器用主体中的 accountId 重新读取数据库，
 * 检查账号是否仍存在、启用状态和 row_version，并用最新身份与权限替换 SecurityContext。</p>
 *
 * <p>过滤器位于 Spring Security 链中。未认证请求原样继续；数据库暂时不可用时返回 500，
 * 不把临时故障误判成账号失效；确认账号已删除、停用或凭证版本过期时才销毁当前 Session。</p>
 */
public class AccountRefreshFilter extends OncePerRequestFilter {
    private final AccountAuthService accountAuthService;
    private final ObjectMapper objectMapper;

    public AccountRefreshFilter(AccountAuthService accountAuthService, ObjectMapper objectMapper) {
        this.accountAuthService = accountAuthService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // 登录、公开端点和匿名请求可能没有本系统主体；过滤器不在这里执行登录校验或改变其身份。
        if (authentication == null || !authentication.isAuthenticated()
            || !(authentication.getPrincipal() instanceof AccountPrincipal)) {
            filterChain.doFilter(request, response);
            return;
        }

        AccountPrincipal previous = (AccountPrincipal) authentication.getPrincipal();
        AccountPrincipal current;
        try {
            // 使用 accountId 重新加载账号、身份和有效授权；其他 Session 字段只保留首次临时凭证流程标记。
            current = accountAuthService.refreshPrincipal(previous.getAccountId(),
                previous.isTemporaryCredentialLogin());
        } catch (IllegalStateException exception) {
            // 数据库角色代码未知或人员关联缺失表示主体数据不再能安全构造，按无效账号处理。
            current = null;
        } catch (DataAccessException exception) {
            // 数据库故障属于服务暂不可用，不清除会话；请求可在服务恢复后重试。
            writeError(response, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务暂时无法处理请求");
            return;
        }
        if (current == null) {
            // 账号可能已删除，或者必要的账号关联已损坏；该 Session 不再具备可用主体。
            invalidate(request);
            writeError(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "登录状态已失效");
            return;
        }
        if (!current.isEnabled()) {
            // 停用账号不能继续使用会话；返回 403 以区别于单纯的“尚未登录”。
            invalidate(request);
            writeError(response, HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "账号已停用");
            return;
        }
        if (current.getAccountVersion() != previous.getAccountVersion()) {
            // 密码设置/重置会递增 row_version。当前主体旧于数据库版本时，销毁该旧会话并要求重新认证。
            invalidate(request);
            writeError(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "账号凭证已更新，请重新登录");
            return;
        }

        // 保留请求来源等 Web 认证细节，但用刚从数据库读取的主体和 authorities 替换旧认证对象。
        UsernamePasswordAuthenticationToken refreshed = new UsernamePasswordAuthenticationToken(
            current, null, current.getAuthorities());
        refreshed.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(refreshed);
        SecurityContextHolder.setContext(context);

        if (current.isMustChangePassword() && !allowedDuringPasswordChange(request)) {
            // 首次登录状态下只开放查询本人状态、登出和提交改密三个路径；其他业务 API 即使角色允许也先拒绝。
            writeError(response, HttpStatus.FORBIDDEN, "PASSWORD_CHANGE_REQUIRED", "首次登录请先修改密码");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean allowedDuringPasswordChange(HttpServletRequest request) {
        // Servlet 的 URI 带应用 contextPath；先去掉它后与 API 契约中的绝对路径精确匹配。
        // 使用方法与路径的组合，避免仅凭路径相同就允许其他 HTTP 动作通过首次改密门禁。
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String method = request.getMethod();
        return ("GET".equals(method) && "/api/auth/me".equals(path))
            || ("POST".equals(method) && ("/api/auth/logout".equals(path)
                || "/api/auth/password-change".equals(path)));
    }

    private void invalidate(HttpServletRequest request) {
        // 清掉服务端保存的 Session，再清除本请求线程的 SecurityContext，避免当前请求继续使用旧主体。
        if (request.getSession(false) != null) request.getSession(false).invalidate();
        SecurityContextHolder.clearContext();
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String code, String message)
        throws IOException {
        // 过滤器运行在 Controller 之前，不能依赖 ControllerAdvice；因此在这里直接写统一 Result JSON。
        response.setStatus(status.value());
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getOutputStream(), Result.failure(code, message, null));
    }
}
