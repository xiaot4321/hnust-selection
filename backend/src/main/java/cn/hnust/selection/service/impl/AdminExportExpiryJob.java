package cn.hnust.selection.service.impl;

import cn.hnust.selection.service.AdminExportService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AdminExportExpiryJob {
    private final AdminExportService service;
    public AdminExportExpiryJob(AdminExportService service) { this.service = service; }
    @Scheduled(fixedDelayString = "${selection.admin-export-cleanup-delay-ms:60000}")
    public void removeExpiredFiles() { service.expireOldExports(); }
}
