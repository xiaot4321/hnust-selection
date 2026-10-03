package cn.hnust.selection.config;

import cn.hnust.selection.security.AccountAuthenticationProvider;
import cn.hnust.selection.security.AccountRefreshFilter;
import cn.hnust.selection.security.JsonAccessDeniedHandler;
import cn.hnust.selection.security.JsonAuthenticationEntryPoint;
import cn.hnust.selection.service.AccountAuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

/**
 * Spring Security 认证、Session、CSRF 和 URL 访问规则配置。
 * URL 角色规则提供第一层入口限制；具体管理员业务仍需在 Service 层核验 capability 与学院/批次范围。
 */
@Configuration
@EnableWebSecurity
@EnableGlobalMethodSecurity(prePostEnabled = true)
public class SecurityConfig extends WebSecurityConfigurerAdapter {
    private final AccountAuthenticationProvider accountAuthenticationProvider;
    private final AccountAuthService accountAuthService;
    private final ObjectMapper objectMapper;
    @Value("${server.servlet.session.cookie.secure:true}")
    private boolean secureCookies;

    public SecurityConfig(AccountAuthenticationProvider accountAuthenticationProvider,
                          AccountAuthService accountAuthService, ObjectMapper objectMapper) {
        this.accountAuthenticationProvider = accountAuthenticationProvider;
        this.accountAuthService = accountAuthService;
        this.objectMapper = objectMapper;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // 正式密码与临时凭证都只存 BCrypt 哈希。强度 10 是当前本地开发基线；明文不会写入数据库。
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public CookieCsrfTokenRepository csrfTokenRepository() {
        // CSRF Cookie 需允许前端 JavaScript 读取，才能把令牌放进 X-XSRF-TOKEN 请求头。
        // 该设置只对 CSRF Cookie 生效；HNUSTSESSION 仍由 Servlet Session 配置为 HttpOnly。
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setSecure(secureCookies);
        return repository;
    }

    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint() {
        // 未登录访问受保护资源时，以项目统一的 JSON Result 格式返回 401。
        return new JsonAuthenticationEntryPoint(objectMapper);
    }

    @Bean
    public AccessDeniedHandler accessDeniedHandler() {
        // 已登录但角色、授权或 CSRF 校验不通过时，返回统一的 JSON 403。
        return new JsonAccessDeniedHandler(objectMapper);
    }

    @Bean
    public AccountRefreshFilter accountRefreshFilter() {
        // 该过滤器在授权判断前刷新当前主体，及时使用账号的最新状态和授权。
        return new AccountRefreshFilter(accountAuthService, objectMapper);
    }

    @Bean
    public FilterRegistrationBean<AccountRefreshFilter> accountRefreshFilterRegistration() {
        // 过滤器只应在 Spring Security 链中运行；禁用 Servlet 容器的自动注册，避免提前运行两次。
        FilterRegistrationBean<AccountRefreshFilter> registration =
            new FilterRegistrationBean<AccountRefreshFilter>(accountRefreshFilter());
        registration.setEnabled(false);
        return registration;
    }

    @Override
    protected void configure(AuthenticationManagerBuilder auth) {
        // 仅使用自定义账号认证 Provider，从数据库校验本地账号和 BCrypt 凭证。
        auth.authenticationProvider(accountAuthenticationProvider);
    }

    @Override
    @Bean
    public AuthenticationManager authenticationManagerBean() throws Exception {
        return super.authenticationManagerBean();
    }

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http
            .csrf()
                // 基于 Cookie + 请求头校验 CSRF。即便登录为公开端点，POST 仍受 CSRF 保护。
                .csrfTokenRepository(csrfTokenRepository())
                .withObjectPostProcessor(new ObjectPostProcessor<CsrfFilter>() {
                    @Override
                    public CsrfFilter postProcess(CsrfFilter filter) {
                        filter.setAccessDeniedHandler(accessDeniedHandler());
                        return filter;
                    }
                })
            .and()
            .sessionManagement()
                // 懒创建服务端 Session；认证成功时迁移并轮换 Session ID。
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .sessionFixation().migrateSession()
            .and()
            .exceptionHandling()
                .authenticationEntryPoint(authenticationEntryPoint())
                .accessDeniedHandler(accessDeniedHandler())
            .and()
            .authorizeRequests()
                // 规则按从具体到宽泛的顺序配置。公开入口仅包括健康检查与登录；写请求仍受 CSRF 保护。
                .antMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                .antMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                .antMatchers(HttpMethod.GET, "/api/auth/me").authenticated()
                .antMatchers(HttpMethod.POST, "/api/auth/logout", "/api/auth/password-change").authenticated()
                .antMatchers("/api/admin/admin-accounts", "/api/admin/admin-accounts/**")
                    // 该精确规则必须位于 /api/admin/** 之前：除 ADMIN 角色外还要求总管理员账户管理能力。
                    .access("hasRole('ADMIN') and hasAuthority('ADMIN_ACCOUNT_MANAGER')")
                .antMatchers("/api/admin/**").hasRole("ADMIN")
                .antMatchers("/api/students/**").hasRole("STUDENT")
                // /api/teachers 公开目录允许所有已登录角色；仅 /me 属于导师本人工作台。
                .antMatchers("/api/teachers/me", "/api/teachers/me/**").hasRole("TEACHER")
                .anyRequest().authenticated()
            .and()
            .addFilterBefore(accountRefreshFilter(), UsernamePasswordAuthenticationFilter.class)
            .formLogin().disable()
            .httpBasic().disable()
            .logout().disable();
    }
}
