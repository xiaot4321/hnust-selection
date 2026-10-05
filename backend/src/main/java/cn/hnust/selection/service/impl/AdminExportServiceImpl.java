package cn.hnust.selection.service.impl;

import cn.hnust.selection.entity.AccountEntity;
import cn.hnust.selection.entity.SelectionBatchEntity;
import cn.hnust.selection.exception.ApiException;
import cn.hnust.selection.repository.AccountRepository;
import cn.hnust.selection.repository.AdminExportRepository;
import cn.hnust.selection.repository.AdminExportRepository.ExportData;
import cn.hnust.selection.repository.AdminExportRepository.Job;
import cn.hnust.selection.repository.SelectionBatchRepository;
import cn.hnust.selection.request.CreateAdminExportRequest;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.AccountAuthorizationService;
import cn.hnust.selection.service.AdminExportService;
import cn.hnust.selection.service.PrivateFileStorage;
import cn.hnust.selection.service.PrivateFileStorage.StoredPrivateFile;
import cn.hnust.selection.vo.AdminExportJobVO;
import cn.hnust.selection.vo.SelectionBatchSummaryVO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

@Service
public class AdminExportServiceImpl implements AdminExportService {
    private static final String COLLEGE_ADMIN = "COLLEGE_ADMIN";
    private static final String BATCH_MANAGER = "BATCH_MANAGER";
    private static final String ADMIN = "ADMIN";
    private static final String FIELD_SET = "fullName,studentNo,majorName,result";
    private static final Pattern UUID_V4 = Pattern.compile("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    private final AdminExportRepository repository;
    private final SelectionBatchRepository batchRepository;
    private final AccountRepository accountRepository;
    private final AccountAuthorizationService authorizationService;
    private final PrivateFileStorage fileStorage;
    private final Executor exportExecutor;

    public AdminExportServiceImpl(AdminExportRepository repository, SelectionBatchRepository batchRepository,
        AccountRepository accountRepository, AccountAuthorizationService authorizationService,
        PrivateFileStorage fileStorage, @Qualifier("adminExportExecutor") Executor exportExecutor) {
        this.repository=repository; this.batchRepository=batchRepository; this.accountRepository=accountRepository;
        this.authorizationService=authorizationService; this.fileStorage=fileStorage; this.exportExecutor=exportExecutor;
    }

    @Override
    @Transactional
    public AdminExportJobVO create(AccountPrincipal actor, Long batchId, CreateAdminExportRequest request, String idempotencyKey) {
        String key = normalizeKey(idempotencyKey);
        String type = request == null || request.getExportType() == null ? "" : request.getExportType().trim().toUpperCase(Locale.ROOT);
        if (!("BATCH_STATISTICS".equals(type) || "BATCH_MATCH_RESULTS".equals(type))) {
            throw invalid("导出类型只能是 BATCH_STATISTICS 或 BATCH_MATCH_RESULTS");
        }
        if (request.getFormat() != null && !"CSV".equalsIgnoreCase(request.getFormat().trim())) {
            throw invalid("当前仅支持 CSV 导出");
        }
        AccountPrincipal current = refresh(actor, true);
        SelectionBatchEntity batch = batchRepository.findBatch(batchId)
            .orElseThrow(() -> notFound("批次不存在或当前账号无权导出"));
        String basis = requireExportScope(current, batch);
        if (batch.getFrozenRosterAt() == null) throw stateConflict("填报名单冻结后才能生成批次导出");
        String fingerprint = digest(batchId + "|" + type + "|CSV");
        Optional<Job> prior = repository.findByRequest(current.getAccountId(), key);
        if (prior.isPresent()) {
            if (!fingerprint.equals(prior.get().fingerprint)) throw new ApiException("IDEMPOTENCY_KEY_REUSED", "幂等键已用于不同导出请求", HttpStatus.CONFLICT);
            Job job = repository.find(prior.get().id).orElseThrow(() -> notFound("导出任务不存在"));
            requireExportScope(current, batchFor(job));
            return repository.toView(job);
        }
        Long id;
        try { id = repository.create(batchId, current.getAccountId(), type, key, fingerprint); }
        catch (DuplicateKeyException ex) {
            Job job = repository.findByRequest(current.getAccountId(), key).orElseThrow(() -> ex);
            if (!fingerprint.equals(job.fingerprint)) throw new ApiException("IDEMPOTENCY_KEY_REUSED", "幂等键已用于不同导出请求", HttpStatus.CONFLICT);
            return repository.toView(repository.find(job.id).orElseThrow(() -> notFound("导出任务不存在")));
        }
        Job job = repository.find(id).orElseThrow(() -> new IllegalStateException("New export job was not found"));
        repository.recordAccess(current.getAccountId(), job, "EXPORT_CREATE", FIELD_SET,
            basis + ";exportType=" + type + ";retention=24h");
        scheduleGenerationAfterCommit(id);
        return repository.toView(job);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminExportJobVO get(AccountPrincipal actor, Long exportId) {
        AccountPrincipal current = refresh(actor, false);
        Job job = repository.find(exportId).orElseThrow(() -> notFound("导出任务不存在或当前账号无权访问"));
        SelectionBatchEntity batch = batchRepository.findBatch(job.batchId)
            .orElseThrow(() -> notFound("导出任务不存在或当前账号无权访问"));
        requireExportScope(current, batch);
        return repository.toView(job);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SelectionBatchSummaryVO> listBatches(AccountPrincipal actor, Long collegeId) {
        AccountPrincipal current = refresh(actor, false);
        if (collegeId == null || !batchRepository.findActiveCollege(collegeId).isPresent()) throw notFound("学院不存在或当前账号无权访问");
        List<SelectionBatchSummaryVO> candidates = batchRepository.listBatches(collegeId, null);
        boolean collegeScope = authorizationService.hasCapability(current, "COLLEGE_ADMIN", collegeId, null);
        if (collegeScope) return candidates;
        List<SelectionBatchSummaryVO> allowed = new java.util.ArrayList<SelectionBatchSummaryVO>();
        for (SelectionBatchSummaryVO batch : candidates) {
            if (authorizationService.hasCapability(current, "BATCH_MANAGER", collegeId, batch.getId())) allowed.add(batch);
        }
        if (allowed.isEmpty() && !authorizationService.hasCapability(current, "BATCH_MANAGER", collegeId, null)) {
            throw notFound("学院不存在或当前账号无权访问");
        }
        return allowed;
    }

    @Override
    @Transactional
    public ExportFile download(AccountPrincipal actor, Long exportId) {
        AccountPrincipal current = refresh(actor, false);
        Job job = repository.find(exportId).orElseThrow(() -> notFound("导出文件不存在或当前账号无权访问"));
        SelectionBatchEntity batch = batchRepository.findBatch(job.batchId)
            .orElseThrow(() -> notFound("导出文件不存在或当前账号无权访问"));
        String basis = requireExportScope(current, batch);
        if (!repository.isLive(exportId)) throw new ApiException("EXPORT_EXPIRED", "导出文件已过期或尚未生成", HttpStatus.GONE);
        if (job.storageKey == null || job.filename == null) throw new ApiException("EXPORT_NOT_READY", "导出文件尚未生成", HttpStatus.CONFLICT);
        try {
            byte[] content = Files.readAllBytes(fileStorage.resolveForRead(job.storageKey));
            repository.recordAccess(current.getAccountId(), job, "EXPORT_DOWNLOAD", FIELD_SET,
                basis + ";exportType=" + job.type + ";rowCount=" + job.rowCount);
            return new ExportFile(job.filename, content);
        } catch (IOException ex) {
            throw new ApiException("EXPORT_FILE_UNAVAILABLE", "导出文件暂时不可用", HttpStatus.GONE);
        }
    }

    @Override
    public void expireOldExports() {
        List<Job> expired = repository.expiredWithFiles();
        for (Job job : expired) {
            if (job.storageKey != null) {
                try { fileStorage.deleteByKey(job.storageKey); }
                catch (IOException ex) { continue; }
            }
            repository.markExpired(job.id);
        }
    }

    private void scheduleGenerationAfterCommit(final Long exportId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Export job must be created within a transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { exportExecutor.execute(() -> generate(exportId)); }
                catch (RuntimeException ex) { repository.markFailed(exportId); }
            }
        });
    }

    private void generate(Long exportId) {
        if (!repository.markProcessing(exportId)) return;
        StoredPrivateFile stored = null;
        try {
            Job job = repository.find(exportId).orElseThrow(() -> new IllegalStateException("Export job disappeared"));
            ExportData data = repository.readData(job.batchId, job.type);
            byte[] csv = toCsv(data.rows);
            String filename = "selection-batch-" + job.batchId + "-" +
                ("BATCH_STATISTICS".equals(job.type) ? "statistics" : "results") + ".csv";
            stored = fileStorage.storeBytes(filename, "text/csv; charset=UTF-8", csv);
            repository.markReady(job.id, stored.getKey(), filename, stored.getSize(), data.rowCount);
        } catch (Exception ex) {
            if (stored != null) fileStorage.delete(stored);
            repository.markFailed(exportId);
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Admin export job {} failed", exportId, ex);
        }
    }

    private SelectionBatchEntity batchFor(Job job) {
        return batchRepository.findBatch(job.batchId).orElseThrow(() -> notFound("批次不存在或当前账号无权导出"));
    }

    private String requireExportScope(AccountPrincipal current, SelectionBatchEntity batch) {
        if (authorizationService.hasCapability(current, COLLEGE_ADMIN, batch.getCollegeId(), null)) return COLLEGE_ADMIN;
        if (authorizationService.hasCapability(current, BATCH_MANAGER, batch.getCollegeId(), batch.getId())) return BATCH_MANAGER;
        throw notFound("批次不存在或当前账号无权导出");
    }

    private AccountPrincipal refresh(AccountPrincipal actor, boolean lock) {
        if (actor == null || actor.getAccountId() == null) throw new ApiException("UNAUTHENTICATED", "登录状态已失效", HttpStatus.UNAUTHORIZED);
        Optional<AccountEntity> found = lock ? accountRepository.findByIdForUpdate(actor.getAccountId()) : accountRepository.findById(actor.getAccountId());
        AccountEntity entity = found.orElseThrow(() -> new ApiException("UNAUTHENTICATED", "登录状态已失效", HttpStatus.UNAUTHORIZED));
        if (!"ACTIVE".equals(entity.getAccountStatus())) throw new ApiException("FORBIDDEN", "账号已停用", HttpStatus.FORBIDDEN);
        if (!ADMIN.equals(entity.getRoleCode())) throw new ApiException("FORBIDDEN", "当前账号不能导出管理数据", HttpStatus.FORBIDDEN);
        try { return accountRepository.toPrincipal(entity, actor.isTemporaryCredentialLogin()); }
        catch (IllegalStateException ex) { throw new ApiException("UNAUTHENTICATED", "登录状态已失效", HttpStatus.UNAUTHORIZED); }
    }

    private static byte[] toCsv(List<String[]> rows) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(new byte[] { (byte)0xEF, (byte)0xBB, (byte)0xBF });
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                if (i > 0) output.write(',');
                String value = row[i] == null ? "" : row[i];
                if (!value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0) value = "'" + value;
                String escaped = value.replace("\"", "\"\"");
                output.write(('"' + escaped + '"').getBytes(StandardCharsets.UTF_8));
            }
            output.write('\r'); output.write('\n');
        }
        return output.toByteArray();
    }

    private static String normalizeKey(String value) {
        if (value == null || !UUID_V4.matcher(value.trim()).matches()) throw invalid("Idempotency-Key 必须是 UUID v4");
        return value.trim().toLowerCase(Locale.ROOT);
    }
    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(); for (byte b : hash) result.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return result.toString();
        } catch (Exception ex) { throw new IllegalStateException("SHA-256 is unavailable", ex); }
    }
    private static ApiException invalid(String message) { return new ApiException("INVALID_ARGUMENT", message, HttpStatus.BAD_REQUEST); }
    private static ApiException notFound(String message) { return new ApiException("NOT_FOUND", message, HttpStatus.NOT_FOUND); }
    private static ApiException stateConflict(String message) { return new ApiException("STATE_CONFLICT", message, HttpStatus.CONFLICT); }
}
