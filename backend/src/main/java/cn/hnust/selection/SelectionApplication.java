package cn.hnust.selection;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 应用入口。
 *
 * <p>启用 Spring 定时调度，使批次填报窗口可以在配置的开始时刻自动开窗并冻结名单/导师范围；
 * 调度间隔由 {@code selection.batch-scheduler-delay-ms} 配置，默认值在调度 Job 上定义。</p>
 */
@SpringBootApplication
@EnableScheduling
public class SelectionApplication {

    public static void main(String[] args) {
        SpringApplication.run(SelectionApplication.class, args);
    }
}
