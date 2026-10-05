package cn.hnust.selection.service.impl;

import cn.hnust.selection.common.PageResult;
import cn.hnust.selection.enums.AccountRole;
import cn.hnust.selection.exception.ApiException;
import cn.hnust.selection.repository.TeacherWorkspaceRepository;
import cn.hnust.selection.repository.TeacherWorkspaceRepository.Operation;
import cn.hnust.selection.repository.TeacherWorkspaceRepository.BulkSortItem;
import cn.hnust.selection.repository.TeacherWorkspaceRepository.QuotaSnapshot;
import cn.hnust.selection.repository.TeacherWorkspaceRepository.RoundApplicationContext;
import cn.hnust.selection.repository.TeacherWorkspaceRepository.StageContext;
import cn.hnust.selection.repository.TeacherWorkspaceRepository.SupplementContext;
import cn.hnust.selection.request.DecideTeacherApplicationRequest;
import cn.hnust.selection.request.BulkDecideTeacherApplicationsRequest;
import cn.hnust.selection.request.SendTeacherApplicationNoticeRequest;
import cn.hnust.selection.request.UpdateTeacherProfileRequest;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.TeacherWorkspaceService;
import cn.hnust.selection.vo.TeacherApplicationVO;
import cn.hnust.selection.vo.TeacherBatchSummaryVO;
import cn.hnust.selection.vo.TeacherDecisionVO;
import cn.hnust.selection.vo.TeacherNoticeVO;
import cn.hnust.selection.vo.TeacherProfileVO;
import cn.hnust.selection.vo.TeacherSupplementApplicationVO;
import cn.hnust.selection.vo.TeacherBulkDecisionAcceptedVO;
import cn.hnust.selection.vo.TeacherBulkOperationVO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class TeacherWorkspaceServiceImpl implements TeacherWorkspaceService {
    private static final Pattern UUID_V4 = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");
    private final TeacherWorkspaceRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final TaskExecutor bulkTaskExecutor;
    public TeacherWorkspaceServiceImpl(TeacherWorkspaceRepository repository, PlatformTransactionManager transactionManager,
        @Qualifier("teacherBulkTaskExecutor") TaskExecutor bulkTaskExecutor) {
        this.repository = repository; this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.bulkTaskExecutor = bulkTaskExecutor;
    }

    @Override
    @Transactional(readOnly = true)
    public TeacherProfileVO getProfile(AccountPrincipal actor) {
        return repository.findProfile(requireTeacher(actor)).orElseThrow(() -> notFound("导师资料不存在"));
    }

    @Override
    @Transactional
    public TeacherProfileVO updateProfile(AccountPrincipal actor, String ifMatch, UpdateTeacherProfileRequest request) {
        Long teacherId = requireTeacher(actor);
        if (request == null) throw invalid("请填写个人简介或研究方向");
        if (ifMatch == null || ifMatch.trim().isEmpty()) {
            throw new ApiException("PRECONDITION_REQUIRED", "更新资料必须提供 If-Match", HttpStatus.PRECONDITION_REQUIRED);
        }
        if (!repository.lockTeacher(teacherId, actor.getAccountId())) throw notFound("导师资料不存在");
        TeacherProfileVO current = repository.findProfile(teacherId).orElseThrow(() -> notFound("导师资料不存在"));
        if (!current.getEtag().equals(ifMatch.trim())) {
            throw new ApiException("PRECONDITION_FAILED", "资料已更新，请重新读取后再保存", HttpStatus.PRECONDITION_FAILED);
        }
        String directions = clean(request.getResearchDirections());
        String biography = clean(request.getBiography());
        if (directions.length() > 2000 || biography.length() > 10000) throw invalid("研究方向或个人简介超出长度限制");
        int nextVersion = current.getVersionNo().intValue() + 1;
        if ("DRAFT".equals(current.getReviewStatus())) {
            repository.updateDraftProfile(teacherId, current.getVersionNo().intValue(), directions, biography);
        } else {
            repository.insertProfileSubmission(teacherId, nextVersion, directions, biography, actor.getAccountId());
        }
        long operationId = repository.insertOperation(actor.getAccountId(), null, "TEACHER_PROFILE_SUBMIT",
            UUID.randomUUID().toString(), hash("PROFILE|" + teacherId + "|" + nextVersion + "|" + directions + "|" + biography));
        repository.insertAudit(actor.getAccountId(), null, "TEACHER_PUBLIC_PROFILE_VERSION", Long.valueOf(nextVersion),
            "TEACHER_PROFILE_SUBMIT", null, "{\"versionNo\":" + nextVersion + ",\"reviewStatus\":\"PENDING_REVIEW\"}", operationId);
        repository.completeOperation(operationId);
        return repository.findProfile(teacherId).orElseThrow(() -> new ApiException("INTERNAL_ERROR", "资料提交后无法读取", HttpStatus.INTERNAL_SERVER_ERROR));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<TeacherApplicationVO> roundApplications(AccountPrincipal actor, Long batchId, int roundNo, int pageNo, int pageSize) {
        Long teacherId = requireTeacher(actor);
        validatePage(pageNo, pageSize);
        if (roundNo < 1 || roundNo > 3) throw invalid("轮次只能是 1、2 或 3");
        if (!repository.ownsBatch(teacherId, batchId)) throw notFound("未找到本人在该批次的业务范围");
        StageContext stage = repository.findRoundStage(teacherId, batchId, roundNo).orElseThrow(() -> notFound("当前轮次不存在"));
        Timestamp now = repository.databaseUtcNow();
        if (!isOpen(stage.batchStatus, stage.currentStageId, stage.stageId, stage.stageStatus, stage.startAt, stage.endAt, now)) {
            throw new ApiException("STATE_CONFLICT", "当前轮次未开放或已关闭", HttpStatus.CONFLICT);
        }
        return new PageResult<TeacherApplicationVO>(repository.listRoundApplications(teacherId, batchId, roundNo, pageNo, pageSize),
            repository.countRoundApplications(teacherId, batchId, roundNo), pageNo, pageSize);
    }

    @Override
    @Transactional
    public TeacherDecisionVO decideRoundApplication(AccountPrincipal actor, Long batchId, Long applicationId,
        DecideTeacherApplicationRequest request, String idempotencyKey) {
        return decideRoundApplication(actor, batchId, applicationId, request, idempotencyKey, "TEACHER_DECISION");
    }

    private TeacherDecisionVO decideRoundApplication(AccountPrincipal actor, Long batchId, Long applicationId,
        DecideTeacherApplicationRequest request, String idempotencyKey, String closeReason) {
        Long teacherId = requireTeacher(actor);
        String decision = requireDecision(request);
        String key = normalizeKey(idempotencyKey);
        String action = "TEACHER_ROUND_DECISION";
        String fingerprint = hash(action + "|" + batchId + "|" + applicationId + "|" + decision);
        if (!repository.lockBatchForTeacher(teacherId, batchId)) throw notFound("未找到本人在该批次的业务范围");
        Optional<Operation> prior = repository.findOperation(actor.getAccountId(), action, key);
        if (prior.isPresent()) {
            validateReplay(prior.get(), fingerprint);
            return currentRoundDecision(teacherId, batchId, applicationId);
        }
        RoundApplicationContext app = repository.lockRoundApplication(teacherId, batchId, applicationId)
            .orElseThrow(() -> notFound("未找到本人当前批次的申请"));
        int roundNo = roundNumber(app.stageCode);
        if (!"ACTIVE".equals(app.batchStatus) || !"OPEN".equals(repositoryStageStatus(app.stageId)) ||
            !app.stageId.equals(app.currentStageId) || roundNo < 1 || roundNo > 3) {
            throw stateConflict("当前申请不属于开放轮次");
        }
        if (!"IN_REVIEW".equals(app.status)) throw stateConflict("申请已处理或不再处于待处理状态");
        StageContext stage = repository.lockRoundStage(teacherId, batchId, roundNo)
            .orElseThrow(() -> notFound("当前轮次不存在"));
        Timestamp now = repository.databaseUtcNow();
        if (!isOpen(stage.batchStatus, stage.currentStageId, stage.stageId, stage.stageStatus, stage.startAt, stage.endAt, now)) {
            throw stateConflict("当前轮次已关闭");
        }
        if (app.currentRelationId != null || "MATCHED".equals(app.matchStatus) || repository.hasYearMatch(app.studentId, app.academicYearId)) {
            throw new ApiException("STUDENT_ALREADY_MATCHED", "该学生已建立有效关系", HttpStatus.CONFLICT);
        }
        if (!app.majorId.equals(app.appliedMajorId) || !app.degreeType.equals(app.appliedDegreeType) ||
            !repository.scopeAllows(batchId, teacherId, app.scopeVersionId, app.majorId, app.degreeType)) {
            throw new ApiException("TEACHER_SCOPE_MISMATCH", "学生身份不再符合该批次冻结范围", HttpStatus.CONFLICT);
        }
        long operationId = repository.insertOperation(actor.getAccountId(), batchId, action, key, fingerprint);
        int occupiedBefore = app.occupiedCount.intValue();
        if ("ADMIT".equals(decision) && repository.updateQuotaOccupied(app.quotaId, occupiedBefore, 1) != 1) {
            throw new ApiException("QUOTA_EXHAUSTED", "导师剩余名额不足", HttpStatus.CONFLICT);
        }
        Long relationId = null;
        if ("ADMIT".equals(decision)) {
            try {
                relationId = repository.insertRelation(app, Long.valueOf(operationId), app.stageCode, applicationId);
                repository.applyMatch(app.studentId, app.academicYearId, app.batchStudentId, relationId,
                    app.matchStatus, app.stageCode, applicationId, Long.valueOf(operationId), Integer.valueOf(roundNo),
                    Integer.valueOf(roundNo));
                repository.insertQuotaLedger(app.quotaId, app.quotaLimit.intValue(), occupiedBefore,
                    occupiedBefore + 1, relationId, Long.valueOf(operationId), actor.getAccountId());
            } catch (DuplicateKeyException ex) {
                throw new ApiException("STUDENT_ALREADY_MATCHED", "该学生已在本学年建立有效关系", HttpStatus.CONFLICT);
            }
        }
        repository.decideRound(applicationId, decision, closeReason);
        repository.insertApplicationEvent("ROUND_APPLICATION", applicationId, app.cycle.intValue(), decision,
            "ADMIT".equals(decision) ? null : closeReason, actor.getAccountId(), Long.valueOf(operationId));
        repository.insertAudit(actor.getAccountId(), batchId, "ROUND_APPLICATION", applicationId, action,
            "{\"status\":\"IN_REVIEW\"}", "{\"decision\":\"" + decision + "\",\"relationId\":" + relationId + "}", Long.valueOf(operationId));
        Long noticeId = repository.insertNoticeRecord(null, batchId, Long.valueOf(operationId), "APPLICATION_DECISION",
            "导师申请处理结果", "导师已处理你在本轮提交的申请，当前处理结果为" + ("ADMIT".equals(decision) ? "录取" : "不录取") + "。");
        repository.addNoticeRecipient(noticeId, app.accountId);
        repository.completeOperation(Long.valueOf(operationId));
        return decisionResult(applicationId, "ADMIT".equals(decision) ? "ADMITTED" : "NOT_ADMITTED", relationId, app.quotaId);
    }

    @Override
    public TeacherBulkDecisionAcceptedVO decideRoundApplicationsBulk(AccountPrincipal actor, Long batchId, int roundNo,
        BulkDecideTeacherApplicationsRequest request, String idempotencyKey) {
        return bulkDecisions(actor, batchId, roundNo, request, idempotencyKey, false);
    }

    @Override
    public TeacherBulkDecisionAcceptedVO decideSupplementApplicationsBulk(AccountPrincipal actor, Long batchId,
        BulkDecideTeacherApplicationsRequest request, String idempotencyKey) {
        return bulkDecisions(actor, batchId, 0, request, idempotencyKey, true);
    }

    private TeacherBulkDecisionAcceptedVO bulkDecisions(AccountPrincipal actor, Long batchId, int roundNo,
        BulkDecideTeacherApplicationsRequest request, String idempotencyKey, boolean supplement) {
        Long teacherId = requireTeacher(actor);
        if (request == null || request.getItems() == null || request.getItems().isEmpty() || request.getItems().size() > 500) throw invalid("批量决定需要 1 至 500 条申请");
        if (roundNo < 1 && !supplement || roundNo > 3) throw invalid("轮次只能是 1、2 或 3");
        String key = normalizeKey(idempotencyKey);
        String action = supplement ? "TEACHER_SUPPLEMENT_BATCH_DECISIONS" : "TEACHER_ROUND_BATCH_DECISIONS";
        List<Long> ids = new ArrayList<Long>();
        java.util.Map<Long, String> decisions = new java.util.LinkedHashMap<Long, String>();
        for (BulkDecideTeacherApplicationsRequest.Item item : request.getItems()) {
            if (item == null || item.getApplicationId() == null || item.getApplicationId().longValue() <= 0 ||
                !("ADMIT".equals(item.getDecision()) || "NOT_ADMITTED".equals(item.getDecision())) || decisions.containsKey(item.getApplicationId())) {
                throw invalid("批量申请 ID 不能重复，决定只能是 ADMIT 或 NOT_ADMITTED");
            }
            ids.add(item.getApplicationId()); decisions.put(item.getApplicationId(), item.getDecision());
        }
        if (!repository.ownsBatch(teacherId, batchId)) throw notFound("未找到本人在该批次的业务范围");
        List<BulkSortItem> sorted = supplement ? repository.sortSupplementBulkItems(teacherId, batchId, ids) :
            repository.sortRoundBulkItems(teacherId, batchId, roundNo, ids);
        if (sorted.size() != ids.size()) throw new ApiException("FORBIDDEN", "部分申请不属于本人当前批次", HttpStatus.FORBIDDEN);
        StringBuilder canonical = new StringBuilder(action).append('|').append(batchId).append('|').append(roundNo);
        for (BulkSortItem item : sorted) canonical.append('|').append(item.applicationId).append(':').append(decisions.get(item.applicationId));
        String fingerprint = hash(canonical.toString());
        Optional<Operation> replay = repository.findOperation(actor.getAccountId(), action, key);
        if (replay.isPresent()) {
            if (!fingerprint.equals(replay.get().fingerprint)) throw new ApiException("IDEMPOTENCY_KEY_REUSED", "幂等键已用于不同请求", HttpStatus.CONFLICT);
            return new TeacherBulkDecisionAcceptedVO(replay.get().id,
                "OK".equals(replay.get().resultCode) ? "COMPLETED" : "PROCESSING");
        }
        final List<BulkSortItem> workItems = sorted;
        final Long operationId = transactionTemplate.execute(status -> {
            long opId = repository.insertOperation(actor.getAccountId(), batchId, action, key, fingerprint);
            repository.insertBulkItems(Long.valueOf(opId), supplement ? "SUPPLEMENT_APPLICATION" : "ROUND_APPLICATION", workItems);
            return Long.valueOf(opId);
        });
        try {
            bulkTaskExecutor.execute(() -> processBulkOperation(actor, batchId, roundNo, supplement, decisions, sorted, operationId));
            return new TeacherBulkDecisionAcceptedVO(operationId, "PROCESSING");
        } catch (TaskRejectedException ex) {
            transactionTemplate.execute(status -> { repository.failBulkOperation(operationId, "WORKER_UNAVAILABLE"); return null; });
            return new TeacherBulkDecisionAcceptedVO(operationId, "FAILED");
        }
    }

    private void processBulkOperation(AccountPrincipal actor, Long batchId, int roundNo, boolean supplement,
        java.util.Map<Long, String> decisions, List<BulkSortItem> sorted, Long operationId) {
        int order = 0;
        for (BulkSortItem item : sorted) {
            final int executionOrder = ++order;
            final String itemDecision = decisions.get(item.applicationId);
            try {
                transactionTemplate.execute(status -> {
                    Long teacherId = actor.getIdentity().getId();
                    if (!repository.accountHasActiveTeacher(teacherId, actor.getAccountId())) {
                        throw new ApiException("FORBIDDEN", "导师账号已停用，批量操作已停止", HttpStatus.FORBIDDEN);
                    }
                    DecideTeacherApplicationRequest one = new DecideTeacherApplicationRequest(); one.setDecision(itemDecision);
                    if (supplement) decideSupplementApplication(actor, batchId, item.applicationId, one, UUID.randomUUID().toString());
                    else decideRoundApplication(actor, batchId, item.applicationId, one, UUID.randomUUID().toString());
                    repository.updateBulkItem(operationId, executionOrder, "SUCCEEDED", null);
                    return null;
                });
            } catch (ApiException ex) {
                final String code = ex.getCode();
                if ("QUOTA_EXHAUSTED".equals(code) && "ADMIT".equals(itemDecision)) {
                    try {
                        transactionTemplate.execute(status -> {
                            Long teacherId = actor.getIdentity().getId();
                            if (!repository.accountHasActiveTeacher(teacherId, actor.getAccountId())) {
                                throw new ApiException("FORBIDDEN", "导师账号已停用，批量操作已停止", HttpStatus.FORBIDDEN);
                            }
                            DecideTeacherApplicationRequest close = new DecideTeacherApplicationRequest(); close.setDecision("NOT_ADMITTED");
                            if (supplement) decideSupplementApplication(actor, batchId, item.applicationId, close, UUID.randomUUID().toString(), "QUOTA_EXHAUSTED");
                            else decideRoundApplication(actor, batchId, item.applicationId, close, UUID.randomUUID().toString(), "QUOTA_EXHAUSTED");
                            repository.updateBulkItem(operationId, executionOrder, "FAILED", code); return null;
                        });
                    } catch (RuntimeException fallbackError) {
                        String failure = fallbackError instanceof ApiException ? ((ApiException) fallbackError).getCode() : "INTERNAL_ERROR";
                        transactionTemplate.execute(status -> { repository.updateBulkItem(operationId, executionOrder, "FAILED", failure); return null; });
                    }
                } else {
                    transactionTemplate.execute(status -> { repository.updateBulkItem(operationId, executionOrder, "FAILED", code); return null; });
                }
            } catch (RuntimeException ex) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Teacher batch operation {} item {} failed unexpectedly", operationId, item.applicationId, ex);
                transactionTemplate.execute(status -> { repository.updateBulkItem(operationId, executionOrder, "FAILED", "INTERNAL_ERROR"); return null; });
            }
        }
        transactionTemplate.execute(status -> { repository.completeOperation(operationId); return null; });
    }

    @Override
    @Transactional(readOnly = true)
    public TeacherBulkOperationVO getOperation(AccountPrincipal actor, Long operationId) {
        requireTeacher(actor);
        String resultCode = repository.findBulkOperationStatus(operationId, actor.getAccountId())
            .orElseThrow(() -> notFound("操作不存在或不属于当前账号"));
        TeacherBulkOperationVO result = new TeacherBulkOperationVO(); result.setOperationId(operationId);
        result.setStatus("OK".equals(resultCode) ? "COMPLETED" : ("FAILED".equals(resultCode) ? "FAILED" : "PROCESSING"));
        List<TeacherBulkOperationVO.Item> items = repository.listBulkOperationItems(operationId);
        for (TeacherBulkOperationVO.Item item : items) item.setMessage(messageForCode(item.getCode()));
        result.setItems(items); return result;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<TeacherSupplementApplicationVO> supplementApplications(AccountPrincipal actor, Long batchId, int pageNo, int pageSize) {
        Long teacherId = requireTeacher(actor);
        validatePage(pageNo, pageSize);
        if (!repository.ownsBatch(teacherId, batchId)) throw notFound("未找到本人在该批次的业务范围");
        if (!repository.hasOpenSupplementPermission(teacherId, batchId)) {
            throw new ApiException("SUPPLEMENT_TEACHER_NOT_ALLOWED", "当前没有开放且授权给本人的补选窗口", HttpStatus.CONFLICT);
        }
        return new PageResult<TeacherSupplementApplicationVO>(repository.listSupplementApplications(teacherId, batchId, pageNo, pageSize),
            repository.countSupplementApplications(teacherId, batchId), pageNo, pageSize);
    }

    @Override
    @Transactional
    public TeacherDecisionVO decideSupplementApplication(AccountPrincipal actor, Long batchId, Long applicationId,
        DecideTeacherApplicationRequest request, String idempotencyKey) {
        return decideSupplementApplication(actor, batchId, applicationId, request, idempotencyKey, "TEACHER_DECISION");
    }

    private TeacherDecisionVO decideSupplementApplication(AccountPrincipal actor, Long batchId, Long applicationId,
        DecideTeacherApplicationRequest request, String idempotencyKey, String closeReason) {
        Long teacherId = requireTeacher(actor);
        String decision = requireDecision(request);
        String key = normalizeKey(idempotencyKey);
        String action = "TEACHER_SUPPLEMENT_DECISION";
        String fingerprint = hash(action + "|" + batchId + "|" + applicationId + "|" + decision);
        if (!repository.lockBatchForTeacher(teacherId, batchId)) throw notFound("未找到本人在该批次的业务范围");
        Optional<Operation> prior = repository.findOperation(actor.getAccountId(), action, key);
        if (prior.isPresent()) {
            validateReplay(prior.get(), fingerprint);
            return currentSupplementDecision(teacherId, batchId, applicationId);
        }
        SupplementContext app = repository.lockSupplementApplication(teacherId, batchId, applicationId)
            .orElseThrow(() -> notFound("未找到本人当前批次的补选申请"));
        Timestamp now = repository.databaseUtcNow();
        if (!"ACTIVE".equals(app.batchStatus) || !"OPEN".equals(app.windowStatus) || !"OPEN".equals(app.stageStatus) ||
            app.currentStageId == null || !app.currentStageId.equals(app.stageId) || app.startAt == null || app.endAt == null ||
            now.before(app.startAt) || !now.before(app.endAt)) throw stateConflict("补选窗口当前不可处理");
        if (!"IN_REVIEW".equals(app.status)) throw stateConflict("补选申请已处理");
        if (app.currentRelationId != null || "MATCHED".equals(app.matchStatus) || !"UNMATCHED".equals(app.matchStatus) ||
            repository.hasYearMatch(app.studentId, app.academicYearId)) {
            throw new ApiException("STUDENT_ALREADY_MATCHED", "该学生已不符合补选录取条件", HttpStatus.CONFLICT);
        }
        if (!repository.hasSupplementPermissionAtSubmission(app.windowId, app.quotaId, app.submittedAt)) {
            throw new ApiException("SUPPLEMENT_TEACHER_NOT_ALLOWED", "该申请提交时未获准参加补选", HttpStatus.CONFLICT);
        }
        Long scopeVersion = repository.frozenScopeVersion(batchId, teacherId);
        if (scopeVersion == null || !repository.scopeAllows(batchId, teacherId, scopeVersion, app.majorId, app.degreeType)) {
            throw new ApiException("TEACHER_SCOPE_MISMATCH", "学生不符合该批次冻结范围", HttpStatus.CONFLICT);
        }
        long operationId = repository.insertOperation(actor.getAccountId(), batchId, action, key, fingerprint);
        int occupiedBefore = app.occupiedCount.intValue();
        if ("ADMIT".equals(decision) && repository.updateQuotaOccupied(app.quotaId, occupiedBefore, 1) != 1) {
            throw new ApiException("QUOTA_EXHAUSTED", "导师剩余名额不足", HttpStatus.CONFLICT);
        }
        Long relationId = null;
        if ("ADMIT".equals(decision)) {
            try {
                relationId = repository.insertRelation(app, Long.valueOf(operationId), "SUPPLEMENT", applicationId);
                repository.applyMatch(app.studentId, app.academicYearId, app.batchStudentId, relationId,
                    app.matchStatus, "SUPPLEMENT", applicationId, Long.valueOf(operationId), null, null);
                repository.insertQuotaLedger(app.quotaId, app.quotaLimit.intValue(), occupiedBefore,
                    occupiedBefore + 1, relationId, Long.valueOf(operationId), actor.getAccountId());
            } catch (DuplicateKeyException ex) {
                throw new ApiException("STUDENT_ALREADY_MATCHED", "该学生已在本学年建立有效关系", HttpStatus.CONFLICT);
            }
        }
        repository.decideSupplement(applicationId, decision, actor.getAccountId(), closeReason);
        repository.releasePendingSupplementSlot(app.studentId);
        repository.insertApplicationEvent("SUPPLEMENT_APPLICATION", applicationId, app.cycle.intValue(), decision,
            "ADMIT".equals(decision) ? null : closeReason, actor.getAccountId(), Long.valueOf(operationId));
        repository.insertAudit(actor.getAccountId(), batchId, "SUPPLEMENT_APPLICATION", applicationId, action,
            "{\"status\":\"IN_REVIEW\"}", "{\"decision\":\"" + decision + "\",\"relationId\":" + relationId + "}", Long.valueOf(operationId));
        Long noticeId = repository.insertNoticeRecord(null, batchId, Long.valueOf(operationId), "APPLICATION_DECISION",
            "补选申请处理结果", "导师已处理你的补选申请，当前处理结果为" + ("ADMIT".equals(decision) ? "录取" : "不录取") + "。");
        repository.addNoticeRecipient(noticeId, app.accountId);
        repository.completeOperation(Long.valueOf(operationId));
        return decisionResult(applicationId, "ADMIT".equals(decision) ? "ADMITTED" : "REJECTED", relationId, app.quotaId);
    }

    @Override
    @Transactional(readOnly = true)
    public TeacherBatchSummaryVO summary(AccountPrincipal actor, Long batchId) {
        Long teacherId = requireTeacher(actor);
        return repository.findSummary(teacherId, batchId).orElseThrow(() -> notFound("未找到本人在该批次的名额和关系"));
    }

    @Override
    @Transactional
    public TeacherNoticeVO sendNotice(AccountPrincipal actor, Long batchId,
        SendTeacherApplicationNoticeRequest request, String idempotencyKey) {
        Long teacherId = requireTeacher(actor);
        if (request == null || request.getApplicationReferences() == null || request.getApplicationReferences().isEmpty()) throw invalid("请选择至少一位当前或历史申请学生");
        if (clean(request.getTitle()).isEmpty() || clean(request.getContent()).isEmpty()) throw invalid("通知标题和正文不能为空");
        if (request.getTitle().trim().length() > 120 || request.getContent().trim().length() > 4000 || request.getApplicationReferences().size() > 500) throw invalid("通知内容或收件人数超出上限");
        if (!repository.ownsBatch(teacherId, batchId)) throw notFound("未找到本人在该批次的业务范围");
        Set<String> seen = new HashSet<String>();
        for (SendTeacherApplicationNoticeRequest.ApplicationReference ref : request.getApplicationReferences()) {
            if (ref == null || ref.getApplicationId() == null || ref.getApplicationId().longValue() <= 0 || ref.getApplicationType() == null ||
                !seen.add(ref.getApplicationType() + ":" + ref.getApplicationId())) throw invalid("申请引用无效或重复");
        }
        String key = normalizeKey(idempotencyKey);
        String action = "TEACHER_APPLICATION_NOTICE";
        String fingerprint = hash(action + "|" + batchId + "|" + clean(request.getTitle()) + "|" + clean(request.getContent()) + "|" + seen.toString());
        Optional<Operation> prior = repository.findOperation(actor.getAccountId(), action, key);
        if (prior.isPresent()) {
            validateReplay(prior.get(), fingerprint);
            Long priorNotice = repository.findAuditObject(prior.get().id, action).orElseThrow(() -> stateConflict("通知操作结果已无法恢复"));
            TeacherNoticeVO result = new TeacherNoticeVO(); result.setNoticeId(priorNotice);
            result.setRecipientCount(Integer.valueOf(repository.countNoticeRecipients(priorNotice))); return result;
        }
        List<Long> recipients = repository.findNoticeRecipients(teacherId, batchId, request.getApplicationReferences());
        if (recipients.isEmpty()) throw new ApiException("FORBIDDEN", "申请引用不属于本人当前批次", HttpStatus.FORBIDDEN);
        long operationId = repository.insertOperation(actor.getAccountId(), batchId, action, key, fingerprint);
        Long noticeId = repository.insertNoticeRecord(actor.getAccountId(), batchId, Long.valueOf(operationId), "TEACHER_MESSAGE",
            clean(request.getTitle()), clean(request.getContent()));
        for (Long recipient : recipients) repository.addNoticeRecipient(noticeId, recipient);
        repository.insertAudit(actor.getAccountId(), batchId, "SITE_NOTICE", noticeId, action, null,
            "{\"recipientCount\":" + recipients.size() + "}", Long.valueOf(operationId));
        repository.completeOperation(Long.valueOf(operationId));
        TeacherNoticeVO result = new TeacherNoticeVO(); result.setNoticeId(noticeId);
        result.setRecipientCount(Integer.valueOf(recipients.size())); result.setSentAt(repository.databaseUtcNow().toInstant().toString()); return result;
    }

    private String repositoryStageStatus(Long stageId) { return repository.stageStatus(stageId); }

    private TeacherDecisionVO currentRoundDecision(Long teacherId, Long batchId, Long applicationId) {
        RoundApplicationContext app = repository.lockRoundApplication(teacherId, batchId, applicationId)
            .orElseThrow(() -> notFound("未找到本人当前批次的申请"));
        return decisionResult(applicationId, app.status, app.currentRelationId, app.quotaId);
    }
    private TeacherDecisionVO currentSupplementDecision(Long teacherId, Long batchId, Long applicationId) {
        SupplementContext app = repository.lockSupplementApplication(teacherId, batchId, applicationId)
            .orElseThrow(() -> notFound("未找到本人当前批次的补选申请"));
        return decisionResult(applicationId, app.status, app.currentRelationId, app.quotaId);
    }
    private TeacherDecisionVO decisionResult(Long applicationId, String status, Long relationId, Long quotaId) {
        QuotaSnapshot quota = repository.currentQuota(quotaId).orElseThrow(() -> notFound("名额账户不存在"));
        TeacherDecisionVO result = new TeacherDecisionVO(); result.setApplicationId(applicationId); result.setStatus(status);
        result.setRelationId(relationId); result.setQuotaLimit(quota.quotaLimit); result.setOccupiedCount(quota.occupiedCount);
        result.setRemainingCount(Integer.valueOf(quota.quotaLimit.intValue() - quota.occupiedCount.intValue()));
        result.setProcessedAt(repository.databaseUtcNow().toInstant().toString()); return result;
    }
    private static boolean isOpen(String batchStatus, Long currentStage, Long stageId, String status,
                                  Timestamp start, Timestamp end, Timestamp now) {
        return "ACTIVE".equals(batchStatus) && "OPEN".equals(status) && currentStage != null && currentStage.equals(stageId) &&
            start != null && end != null && !now.before(start) && now.before(end);
    }
    private static int roundNumber(String stageCode) {
        if (stageCode == null || !stageCode.startsWith("ROUND_")) return -1;
        try { return Integer.parseInt(stageCode.substring(6)); }
        catch (NumberFormatException ex) { return -1; }
    }
    private static void validateReplay(Operation operation, String fingerprint) {
        if (!fingerprint.equals(operation.fingerprint)) throw new ApiException("IDEMPOTENCY_KEY_REUSED", "幂等键已用于不同请求", HttpStatus.CONFLICT);
        if (!"OK".equals(operation.resultCode)) throw new ApiException("REQUEST_IN_PROGRESS", "相同请求仍在处理", HttpStatus.CONFLICT);
    }
    private static String messageForCode(String code) {
        if (code == null) return "处理成功。";
        if ("QUOTA_EXHAUSTED".equals(code)) return "导师剩余名额不足，申请按不录取结案。";
        if ("STUDENT_ALREADY_MATCHED".equals(code)) return "该学生已建立其他有效关系。";
        if ("STATE_CONFLICT".equals(code) || "APPLICATION_NOT_PENDING".equals(code)) return "申请状态或轮次已变化。";
        if ("TEACHER_SCOPE_MISMATCH".equals(code)) return "学生身份不符合本批次冻结范围。";
        if ("SUPPLEMENT_TEACHER_NOT_ALLOWED".equals(code)) return "补选许可或窗口已关闭。";
        return code;
    }
    private static String normalizeKey(String key) {
        if (key == null || !UUID_V4.matcher(key).matches()) throw invalid("Idempotency-Key 必须是 UUID v4");
        return key.toLowerCase(Locale.ROOT);
    }
    private static String requireDecision(DecideTeacherApplicationRequest request) {
        if (request == null || !("ADMIT".equals(request.getDecision()) || "NOT_ADMITTED".equals(request.getDecision()))) throw invalid("决定只能是 ADMIT 或 NOT_ADMITTED");
        return request.getDecision();
    }
    private static void validatePage(int pageNo, int pageSize) {
        if (pageNo < 1 || pageSize < 1 || pageSize > 100) throw invalid("分页参数超出范围");
    }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static Long requireTeacher(AccountPrincipal actor) {
        if (actor == null || actor.getRole() != AccountRole.TEACHER || actor.getAccountId() == null ||
            actor.getIdentity() == null || actor.getIdentity().getId() == null) throw new ApiException("FORBIDDEN", "仅导师本人可访问导师工作台", HttpStatus.FORBIDDEN);
        return actor.getIdentity().getId();
    }
    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(); for (byte b : digest) out.append(String.format(Locale.ROOT, "%02x", b & 0xff)); return out.toString();
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 is not available", ex); }
    }
    private static ApiException invalid(String message) { return new ApiException("INVALID_ARGUMENT", message, HttpStatus.BAD_REQUEST); }
    private static ApiException stateConflict(String message) { return new ApiException("STATE_CONFLICT", message, HttpStatus.CONFLICT); }
    private static ApiException notFound(String message) { return new ApiException("NOT_FOUND", message, HttpStatus.NOT_FOUND); }
}
