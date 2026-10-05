package cn.hnust.selection.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码哈希算法配置。
 *
 * <p>把 PasswordEncoder 放在独立配置类中，避免认证服务依赖 SecurityConfig 本身声明的 Bean，
 * 从而形成 SecurityConfig → AuthenticationProvider → AccountAuthService → SecurityConfig 的创建环。</p>
 */
@Configuration
public class PasswordEncoderConfig {
    /** 正式密码和临时凭证均只保存 BCrypt 哈希；强度 10 与当前本地开发基线一致。 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
