package cn.hnust.selection.service.impl;

import cn.hnust.selection.common.PageResult;
import cn.hnust.selection.entity.AccountEntity;
import cn.hnust.selection.exception.ApiException;
import cn.hnust.selection.repository.AccountRepository;
import cn.hnust.selection.repository.AdminRelationAdjustmentRepository;
import cn.hnust.selection.repository.AdminRelationAdjustmentRepository.FrozenTeacherScope;
import cn.hnust.selection.repository.AdminRelationAdjustmentRepository.Operation;
import cn.hnust.selection.repository.AdminRelationAdjustmentRepository.QuotaAccount;
import cn.hnust.selection.repository.AdminRelationAdjustmentRepository.Target;
import cn.hnust.selection.repository.SelectionBatchRepository;
import cn.hnust.selection.request.AdjustMatchingRelationRequest;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.AccountAuthorizationService;
import cn.hnust.selection.service.AdminRelationAdjustmentService;
import cn.hnust.selection.vo.AdminMatchingRelationVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class AdminRelationAdjustmentServiceImpl implements AdminRelationAdjustmentService {
    private static final String ADMIN = "ADMIN", MANAGER = "BATCH_MANAGER", AUDIT = "BATCH_AUDIT";
    private static final Pattern UUID_V4 = Pattern.compile("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    private final AdminRelationAdjustmentRepository repository;
    private final AccountRepository accounts;
    private final AccountAuthorizationService authorizations;
    private final SelectionBatchRepository batches;
    private final ObjectMapper mapper;

    public AdminRelationAdjustmentServiceImpl(AdminRelationAdjustmentRepository repository, AccountRepository accounts,
        AccountAuthorizationService authorizations, SelectionBatchRepository batches, ObjectMapper mapper) {
        this.repository=repository; this.accounts=accounts; this.authorizations=authorizations; this.mapper=mapper;
        this.batches=batches;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<AdminMatchingRelationVO> list(AccountPrincipal actor, Long batchId, int pageNo, int pageSize) {
        if (pageNo < 1 || pageSize < 1 || pageSize > 100) throw invalid("分页参数无效");
        AccountPrincipal current = refresh(actor, false);
        cn.hnust.selection.entity.SelectionBatchEntity batch = batches.findBatch(batchId)
            .orElseThrow(() -> notFound("批次不存在或当前账号无权访问"));
        requireReadScope(current, batch.getCollegeId(), batch.getId());
        List<AdminMatchingRelationVO> items = repository.list(batchId, pageNo, pageSize);
        return new PageResult<AdminMatchingRelationVO>(items, repository.count(batchId), pageNo, pageSize);
    }

    @Override
    @Transactional
    public AdminMatchingRelationVO adjust(AccountPrincipal actor, Long relationId, AdjustMatchingRelationRequest request,
        String ifMatch, String idempotencyKey) {
        if (request == null) throw invalid("缺少关系调整信息");
        String key = normalizeKey(idempotencyKey);
        String type = request.getAdjustmentType() == null ? "" : request.getAdjustmentType().trim().toUpperCase(Locale.ROOT);
        if (!("REVOKE".equals(type) || "RESTORE".equals(type) || "REASSIGN".equals(type))) throw invalid("关系调整类型无效");
        String reason = request.getReason() == null ? "" : request.getReason().trim();
        if (reason.isEmpty()) throw invalid("关系调整必须填写原因");
        String comment = request.getApprovalComment() == null ? null : request.getApprovalComment().trim();
        if ("REASSIGN".equals(type) && (request.getNewTeacherId() == null || request.getNewTeacherId().longValue() <= 0L)) {
            throw invalid("改派必须指定新导师");
        }
        if (!"REASSIGN".equals(type) && request.getNewTeacherId() != null) throw invalid("仅改派时可指定新导师");
        AccountPrincipal current = refresh(actor, true);
        Target target = repository.lockTarget(relationId).orElseThrow(() -> notFound("关系不存在或当前账号无权访问"));
        requireManagerScope(current, target);
        String suppliedTag = stripQuotes(ifMatch == null ? "" : ifMatch.trim());
        if (suppliedTag.isEmpty()) throw new ApiException("PRECONDITION_REQUIRED", "调整关系前请读取关系版本并发送 If-Match", HttpStatus.PRECONDITION_REQUIRED);
        String fingerprint = digest(relationId + "|" + type + "|" + request.getNewTeacherId() + "|" + reason + "|" + comment + "|" + suppliedTag);
        Optional<Operation> previous = repository.findOperation(current.getAccountId(), key);
        if (previous.isPresent()) {
            Operation operation = previous.get();
            if (!fingerprint.equals(operation.fingerprint)) throw new ApiException("IDEMPOTENCY_KEY_REUSED", "幂等键已用于不同关系调整", HttpStatus.CONFLICT);
            if (!"OK".equals(operation.resultCode)) throw new ApiException("REQUEST_IN_PROGRESS", "关系调整仍在处理中", HttpStatus.CONFLICT);
            return repository.findView(relationId).orElseThrow(() -> notFound("关系不存在"));
        }
        if (!AdminRelationAdjustmentRepository.etag(relationId, target.rowVersion).equals(suppliedTag)) {
            throw new ApiException("PRECONDITION_FAILED", "师生关系已变化，请刷新后重试", HttpStatus.PRECONDITION_FAILED);
        }
        if ("ARCHIVED".equals(target.batchStatus)) throw stateConflict("批次已归档，请先解除归档");
        if (!("ACTIVE".equals(target.batchStatus) || "PAUSED".equals(target.batchStatus) || "COMPLETED".equals(target.batchStatus))) {
            throw stateConflict("当前批次状态不允许调整师生关系");
        }
        if (!"REVOKE".equals(type) && "REVOKED".equals(target.relationStatus) &&
            "IDENTITY_CORRECTION".equals(target.matchReason)) {
            throw deferred("身份纠错导致的关系变更属于 TODO-49 暂缓边界，本期不办理恢复或改派");
        }
        if ("RESTORE".equals(type)) {
            if (!"REVOKED".equals(target.relationStatus) || !"UNMATCHED".equals(target.matchStatus) || target.currentRelationId != null) {
                throw stateConflict("恢复要求原关系已撤销且学生当前未匹配");
            }
            if (!"RELATION_REVOKED".equals(target.matchReason)) throw stateConflict("该学生当前未匹配状态不是由关系撤销产生");
        } else if (!"LOCKED".equals(target.relationStatus) || !"MATCHED".equals(target.matchStatus) ||
            target.currentRelationId == null || !target.currentRelationId.equals(target.relationId)) {
            throw stateConflict("撤销或改派要求学生当前持有该有效锁定关系");
        }

        FrozenTeacherScope frozenScope = null;
        if ("REASSIGN".equals(type)) {
            frozenScope = repository.findFrozenTeacherScope(target, request.getNewTeacherId()).orElseThrow(
                () -> deferred("目标导师缺少已冻结的本批次可报范围，属于 TODO-49 暂缓边界，本期不办理改派"));
            int degreeMask = degreeMask(target.degreeType);
            if ((frozenScope.allowedDegreeMask & degreeMask) == 0 || !frozenScope.majorAllowed) {
                throw stateConflict("目标导师已冻结的可报范围不包含该学生当前专业和学位类型");
            }
        }

        Map<Long, QuotaAccount> lockedQuotas = repository.lockQuotaAccounts(target.quotaId,
            frozenScope == null ? null : frozenScope.quotaId);
        QuotaAccount originalQuota = lockedQuotas.get(target.quotaId);
        if (originalQuota == null) throw stateConflict("原导师名额账户不存在");
        if (!target.batchId.equals(originalQuota.batchId)) throw stateConflict("原关系名额账户不属于当前批次");
        if (frozenScope != null && !lockedQuotas.containsKey(frozenScope.quotaId)) throw stateConflict("目标导师名额账户不存在");
        target.teacherId = originalQuota.teacherId;
        target.quotaLimit = originalQuota.quotaLimit;
        target.occupiedCount = originalQuota.occupiedCount;
        QuotaAccount replacementQuota = frozenScope == null ? null : lockedQuotas.get(frozenScope.quotaId);
        if (replacementQuota != null && request.getNewTeacherId().equals(originalQuota.teacherId)) {
            throw invalid("新导师必须与原导师不同");
        }
        if ("RESTORE".equals(type) && originalQuota.occupiedCount >= originalQuota.quotaLimit) {
            throw stateConflict("原导师名额已满，不能恢复关系");
        }
        if (replacementQuota != null) {
            if (!target.batchId.equals(replacementQuota.batchId)) throw stateConflict("目标导师名额账户不属于当前批次");
            if (!request.getNewTeacherId().equals(replacementQuota.teacherId)) throw stateConflict("目标导师名额账户与导师不一致");
            if (replacementQuota.occupiedCount >= replacementQuota.quotaLimit) throw stateConflict("目标导师没有剩余名额，不能改派");
        }

        Long operationId;
        try { operationId = repository.insertOperation(current.getAccountId(), target, key, fingerprint); }
        catch (DuplicateKeyException ex) {
            Operation concurrent = repository.findOperation(current.getAccountId(), key).orElseThrow(() -> ex);
            if (!fingerprint.equals(concurrent.fingerprint)) throw new ApiException("IDEMPOTENCY_KEY_REUSED", "幂等键已用于不同关系调整", HttpStatus.CONFLICT);
            return repository.findView(relationId).orElseThrow(() -> notFound("关系不存在"));
        }
        if ("REVOKE".equals(type)) {
            if (!repository.revokeRelation(target)) throw precondition("师生关系已变化，请刷新后重试");
            if (repository.releaseYearSlot(target) != 1) throw stateConflict("有效关系年度槽位不一致，未执行调整");
            if (!repository.decrementQuota(originalQuota)) throw stateConflict("导师名额账户不一致，未执行调整");
            if (!repository.markUnmatched(target)) throw stateConflict("学生匹配状态已变化，未执行调整");
            Long adjustmentId = repository.insertAdjustment(target, type, current.getAccountId(), operationId, reason, comment, null, null);
            repository.insertMatchEvent(target, operationId, adjustmentId, "MATCHED", "UNMATCHED", "RELATION_REVOKED");
            repository.insertQuotaLedger(target, originalQuota, "RELATION_REVOKE", -1, target.relationId,
                current.getAccountId(), operationId, reason);
            repository.insertAudit(target, current.getAccountId(), operationId, "RELATION_REVOKE",
                json("relationStatus", "LOCKED", "rowVersion", target.rowVersion, "matchStatus", "MATCHED", "occupiedCount", originalQuota.occupiedCount),
                json("relationStatus", "REVOKED", "matchStatus", "UNMATCHED", "matchReason", "RELATION_REVOKED",
                    "occupiedCount", originalQuota.occupiedCount - 1), reason, comment);
            repository.notifyAdjustment(target, type, null, operationId);
        } else if ("RESTORE".equals(type)) {
            if (!repository.restoreRelation(target)) throw precondition("原师生关系已变化，请刷新后重试");
            try { repository.insertYearSlot(target); }
            catch (DuplicateKeyException ex) { throw stateConflict("学生本学年已有有效关系，不能恢复该关系"); }
            if (!repository.incrementQuota(originalQuota)) throw stateConflict("原导师名额账户已变化，不能恢复关系");
            if (!repository.markMatchedFromRevoke(target)) throw stateConflict("学生匹配状态已变化，不能恢复关系");
            Long adjustmentId = repository.insertAdjustment(target, type, current.getAccountId(), operationId, reason, comment,
                target.teacherId, target.relationId);
            repository.insertMatchEvent(target, operationId, adjustmentId, "UNMATCHED", "MATCHED", "RELATION_RESTORED");
            repository.insertQuotaLedger(target, originalQuota, "RELATION_RESTORE", 1, target.relationId,
                current.getAccountId(), operationId, reason);
            repository.insertAudit(target, current.getAccountId(), operationId, "RELATION_RESTORE",
                json("relationStatus", "REVOKED", "matchStatus", "UNMATCHED", "matchReason", "RELATION_REVOKED",
                    "occupiedCount", originalQuota.occupiedCount),
                json("relationStatus", "LOCKED", "matchStatus", "MATCHED", "occupiedCount", originalQuota.occupiedCount + 1),
                reason, comment);
            repository.notifyAdjustment(target, type, null, operationId);
        } else {
            if (!repository.revokeRelation(target)) throw precondition("师生关系已变化，请刷新后重试");
            if (!repository.decrementQuota(originalQuota)) throw stateConflict("原导师名额账户不一致，未执行改派");
            if (!repository.incrementQuota(replacementQuota)) throw stateConflict("目标导师名额账户已变化，未执行改派");
            Long adjustmentId = repository.insertAdjustment(target, type, current.getAccountId(), operationId, reason, comment,
                request.getNewTeacherId(), null);
            Long newRelationId = repository.insertReplacementRelation(target, replacementQuota, operationId, adjustmentId);
            repository.setAdjustmentNewRelation(adjustmentId, newRelationId);
            if (repository.replaceYearSlot(target, newRelationId) != 1) throw stateConflict("有效关系年度槽位不一致，未执行改派");
            if (!repository.replaceCurrentRelation(target, newRelationId)) throw stateConflict("学生匹配状态已变化，未执行改派");
            repository.insertMatchEvent(target, operationId, adjustmentId, "MATCHED", "MATCHED", "RELATION_REASSIGNED");
            repository.insertQuotaLedger(target, originalQuota, "RELATION_REASSIGN_OUT", -1, target.relationId,
                current.getAccountId(), operationId, reason);
            repository.insertQuotaLedger(target, replacementQuota, "RELATION_REASSIGN_IN", 1, newRelationId,
                current.getAccountId(), operationId, reason);
            repository.insertAudit(target, current.getAccountId(), operationId, "RELATION_REASSIGN",
                json("relationStatus", "LOCKED", "relationId", target.relationId, "teacherId", target.teacherId,
                    "occupiedCount", originalQuota.occupiedCount, "replacementOccupiedCount", replacementQuota.occupiedCount),
                json("relationStatus", "REVOKED", "replacementRelationId", newRelationId,
                    "replacementTeacherId", request.getNewTeacherId(), "matchStatus", "MATCHED",
                    "occupiedCount", originalQuota.occupiedCount - 1, "replacementOccupiedCount", replacementQuota.occupiedCount + 1),
                reason, comment);
            repository.notifyAdjustment(target, type, request.getNewTeacherId(), operationId);
        }
        repository.completeOperation(operationId);
        return repository.findView(relationId).orElseThrow(() -> new IllegalStateException("Adjusted relation was not found"));
    }

    private int degreeMask(String degreeType) {
        if ("ACADEMIC_MASTER".equals(degreeType)) return 1;
        if ("PROFESSIONAL_MASTER".equals(degreeType)) return 2;
        return 0;
    }

    private void requireReadScope(AccountPrincipal current, Long collegeId, Long batchId) {
        if (authorizations.hasCapability(current, MANAGER, collegeId, batchId) ||
            authorizations.hasCapability(current, AUDIT, collegeId, batchId)) return;
        throw notFound("批次不存在或当前账号无权访问");
    }
    private void requireManagerScope(AccountPrincipal current, Target target) {
        if (!authorizations.hasCapability(current, MANAGER, target.collegeId, target.batchId)) {
            throw notFound("关系不存在或当前账号无权访问");
        }
    }
    private AccountPrincipal refresh(AccountPrincipal actor, boolean lock) {
        if (actor == null || actor.getAccountId() == null) throw new ApiException("UNAUTHENTICATED", "登录状态已失效", HttpStatus.UNAUTHORIZED);
        Optional<AccountEntity> found = lock ? accounts.findByIdForUpdate(actor.getAccountId()) : accounts.findById(actor.getAccountId());
        AccountEntity account = found.orElseThrow(() -> new ApiException("UNAUTHENTICATED", "登录状态已失效", HttpStatus.UNAUTHORIZED));
        if (!"ACTIVE".equals(account.getAccountStatus())) throw new ApiException("FORBIDDEN", "账号已停用", HttpStatus.FORBIDDEN);
        if (!ADMIN.equals(account.getRoleCode())) throw new ApiException("FORBIDDEN", "当前账号不能办理关系调整", HttpStatus.FORBIDDEN);
        try { return accounts.toPrincipal(account, actor.isTemporaryCredentialLogin()); }
        catch (IllegalStateException ex) { throw new ApiException("UNAUTHENTICATED", "登录状态已失效", HttpStatus.UNAUTHORIZED); }
    }
    private String json(Object... pairs) {
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<String, Object>();
        for (int i=0; i+1<pairs.length; i+=2) values.put(String.valueOf(pairs[i]), pairs[i+1]);
        try { return mapper.writeValueAsString(values); } catch (Exception ex) { throw new IllegalStateException("Could not serialize audit snapshot", ex); }
    }
    private static String normalizeKey(String value) {
        if (value == null || !UUID_V4.matcher(value.trim()).matches()) throw invalid("Idempotency-Key 必须是 UUID v4");
        return value.trim().toLowerCase(Locale.ROOT);
    }
    private static String stripQuotes(String value) { return value.startsWith("\"") && value.endsWith("\"") && value.length() > 1 ? value.substring(1, value.length()-1) : value; }
    private static String digest(String value) {
        try { byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder out=new StringBuilder();
            for (byte b:bytes) out.append(String.format(Locale.ROOT, "%02x", b & 0xff)); return out.toString();
        } catch (Exception ex) { throw new IllegalStateException("SHA-256 is unavailable", ex); }
    }
    private static ApiException invalid(String message) { return new ApiException("INVALID_ARGUMENT", message, HttpStatus.BAD_REQUEST); }
    private static ApiException notFound(String message) { return new ApiException("NOT_FOUND", message, HttpStatus.NOT_FOUND); }
    private static ApiException stateConflict(String message) { return new ApiException("STATE_CONFLICT", message, HttpStatus.CONFLICT); }
    private static ApiException deferred(String message) { return new ApiException("BUSINESS_RULE_DEFERRED", message, HttpStatus.CONFLICT); }
    private static ApiException precondition(String message) { return new ApiException("PRECONDITION_FAILED", message, HttpStatus.PRECONDITION_FAILED); }
}
