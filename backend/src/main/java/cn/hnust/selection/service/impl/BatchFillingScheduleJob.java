package cn.hnust.selection.service.impl;

import cn.hnust.selection.service.SelectionBatchManagementService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 批次填报窗口定时任务。
 *
 * <p>这里只负责周期性触发扫描，不使用 JVM 锁或本地缓存判断窗口状态；具体批次筛选、数据库 UTC 时间检查、
 * 行锁和冻结事务都由应用服务及仓储执行，因此多个应用实例也由数据库约束协调。</p>
 */
@Component
public class BatchFillingScheduleJob {
    private final SelectionBatchManagementService service;

    public BatchFillingScheduleJob(SelectionBatchManagementService service) { this.service = service; }

    /** 每次执行结束后等待配置的间隔；默认 5 秒，延迟只影响开窗检查频率，不改变窗口边界。 */
    @Scheduled(fixedDelayString = "${selection.batch-scheduler-delay-ms:5000}")
    public void openDueFillingWindows() { service.openDueFillingWindows(); }
}
