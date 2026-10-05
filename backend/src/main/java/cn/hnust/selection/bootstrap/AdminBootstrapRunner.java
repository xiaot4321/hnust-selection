package cn.hnust.selection.bootstrap;

import cn.hnust.selection.service.SystemBootstrapService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 仅在显式开启时运行的首次管理员本机命令行入口。
 *
 * <p>生产常规启动不设置 {@code app.bootstrap.admin-enabled=true}，因此不会执行初始化逻辑。
 * 使用时还应将 Spring Web 类型设为 none，避免开启 HTTP 服务；临时凭证只写标准输出，不走日志。</p>
 */
@Component
@ConditionalOnProperty(name = "app.bootstrap.admin-enabled", havingValue = "true")
public class AdminBootstrapRunner implements ApplicationRunner {
    private final SystemBootstrapService bootstrapService;
    private final String loginIdentifier;
    private final String collegeCode;
    private final String collegeName;

    public AdminBootstrapRunner(SystemBootstrapService bootstrapService,
                                @Value("${app.bootstrap.admin-login-identifier:}") String loginIdentifier,
                                @Value("${app.bootstrap.admin-college-code:}") String collegeCode,
                                @Value("${app.bootstrap.admin-college-name:}") String collegeName) {
        this.bootstrapService = bootstrapService;
        this.loginIdentifier = loginIdentifier;
        this.collegeCode = collegeCode;
        this.collegeName = collegeName;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 先完整提交事务，随后才展示结果。若控制台输出失败，槽位仍保持占用以确保凭证绝不被重放；
        // 应按 TODO-24 应急流程处理，而不是重新运行初始化命令。
        InitialAdminBootstrapResult result = bootstrapService.initializeInitialAdmin(
            loginIdentifier, collegeCode, collegeName);

        System.out.println();
        System.out.println("=== 总管理员初始化完成 ===");
        System.out.println("登录标识: " + result.getLoginIdentifier());
        System.out.println("学院名称: " + result.getCollegeName());
        System.out.println("学院代码: " + result.getCollegeCode());
        System.out.println("账号 ID: " + result.getAccountId());
        System.out.println("一次性临时凭证（不设到期时间，仅此处显示一次）:");
        System.out.println(result.getTemporaryCredential());
        System.out.println("请立即通过受控线下渠道交付，并在首次登录后设置正式密码。");
        System.out.println("==========================");
    }
}
