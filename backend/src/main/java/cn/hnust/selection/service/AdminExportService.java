package cn.hnust.selection.service;

import cn.hnust.selection.request.CreateAdminExportRequest;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.vo.AdminExportJobVO;
import cn.hnust.selection.vo.SelectionBatchSummaryVO;
import java.util.List;

public interface AdminExportService {
    AdminExportJobVO create(AccountPrincipal actor, Long batchId, CreateAdminExportRequest request, String idempotencyKey);
    AdminExportJobVO get(AccountPrincipal actor, Long exportId);
    ExportFile download(AccountPrincipal actor, Long exportId);
    List<SelectionBatchSummaryVO> listBatches(AccountPrincipal actor, Long collegeId);
    void expireOldExports();

    final class ExportFile {
        private final String filename;
        private final byte[] content;
        public ExportFile(String filename, byte[] content) { this.filename = filename; this.content = content; }
        public String getFilename() { return filename; }
        public byte[] getContent() { return content; }
    }
}
