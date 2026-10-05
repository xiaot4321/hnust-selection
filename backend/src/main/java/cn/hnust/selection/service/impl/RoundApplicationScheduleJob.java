package cn.hnust.selection.service.impl;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 轮次与补选阶段截止推进器；每轮事务由数据库 UTC 时钟和批次行锁裁决。 */
@Component
public class RoundApplicationScheduleJob {
    private final TeacherApplicationLifecycleService service;
    public RoundApplicationScheduleJob(TeacherApplicationLifecycleService service) { this.service = service; }
    @Scheduled(fixedDelayString = "${selection.batch-scheduler-delay-ms:5000}")
    public void advanceSelectionRounds() { service.processActiveBatches(); }
}
