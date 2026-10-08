package cn.hnust.selection.service.impl;

import cn.hnust.selection.entity.AccountEntity;
import cn.hnust.selection.entity.SelectionBatchEntity;
import cn.hnust.selection.entity.TeacherQuotaEntity;
import cn.hnust.selection.entity.TeacherScopeVersionEntity;
import cn.hnust.selection.exception.ApiException;
import cn.hnust.selection.repository.AccountRepository;
import cn.hnust.selection.repository.SelectionBatchRepository;
import cn.hnust.selection.repository.SelectionBatchRepository.OperationRecord;
import cn.hnust.selection.repository.SelectionBatchRepository.PauseEvent;
import cn.hnust.selection.request.BatchScheduleRequest;
import cn.hnust.selection.request.BatchStageScheduleRequest;
import cn.hnust.selection.request.CreateSelectionBatchRequest;
import cn.hnust.selection.request.ExtendRoundRequest;
import cn.hnust.selection.request.ReopenRoundRequest;
import cn.hnust.selection.request.SetTeacherApplicationScopeRequest;
import cn.hnust.selection.request.SetSupplementTeachersRequest;
import cn.hnust.selection.request.SetTeacherQuotaRequest;
import cn.hnust.selection.request.UpdateSelectionBatchRequest;
import cn.hnust.selection.security.AccountAuthorization;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.AccountAuthorizationService;
import cn.hnust.selection.service.SelectionBatchManagementService;
import cn.hnust.selection.vo.AcademicYearOptionVO;
import cn.hnust.selection.vo.BatchStageVO;
import cn.hnust.selection.vo.BatchCollegeOptionVO;
import cn.hnust.selection.vo.BatchTeacherQuotaVO;
import cn.hnust.selection.vo.BatchStatisticsVO;
import cn.hnust.selection.vo.CollegeOptionVO;
import cn.hnust.selection.vo.MajorVO;
import cn.hnust.selection.vo.SelectionBatchDetailVO;
import cn.hnust.selection.vo.SelectionBatchSummaryVO;
import cn.hnust.selection.vo.TeacherApplicationScopeVO;
import cn.hnust.selection.vo.TeacherScopeBatchOptionVO;
import cn.hnust.selection.vo.SupplementTeacherVO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 批次、导师名额与导师招生范围用例实现。
 *
 * <p>Controller 传入的 Principal 仅用于定位账号；每个请求都会从数据库刷新角色和授权，不能信任旧 Session 中的
 * 权限快照。写命令在数据库事务中执行，涉及并发的批次、学年、名额或范围行使用行锁/版本条件；业务操作、
 * 生命周期事件和审计记录随业务数据一起提交。定时开窗按批次分为独立事务，单批失败不会阻塞其他批次。</p>
 */
@Service
public class SelectionBatchManagementServiceImpl implements SelectionBatchManagementService {
    /** 普通管理员批次配置所需能力；总管理员通过统一授权服务隐式获得。 */
    private static final String BATCH_MANAGER = "BATCH_MANAGER";
    private static final String BATCH_AUDIT = "BATCH_AUDIT";
    private static final String ADMIN_ACCOUNT_MANAGER = "ADMIN_ACCOUNT_MANAGER";
    private static final String ADMIN = "ADMIN";
    private static final String TEACHER = "TEACHER";
    /** 排期必须连续覆盖填报和三轮，补选按批次创建时的开关成为可选第五阶段。 */
    private static final List<String> CORE_STAGES = Arrays.asList("FILLING", "ROUND_1", "ROUND_2", "ROUND_3");
    /** 业务命令只接受符合 API 约定的 UUID v4 幂等键。 */
    private static final Pattern UUID_V4 = Pattern.compile(
        "(?i)^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    private final SelectionBatchRepository repository;
    private final AccountRepository accountRepository;
    private final AccountAuthorizationService authorizationService;
    private final ObjectMapper objectMapper;
    /** 定时开窗逐批次使用 REQUIRES_NEW，确保单批回滚不污染其他批次事务。 */
    private final TransactionTemplate transactionTemplate;

    public SelectionBatchManagementServiceImpl(SelectionBatchRepository repository,
        AccountRepository accountRepository, AccountAuthorizationService authorizationService,
        ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.repository = repository; this.accountRepository = accountRepository;
        this.authorizationService = authorizationService; this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 汇总管理员实际可访问的学院，并标记授权能否创建批次。 */
    @Override
    @Transactional(readOnly = true)
    public List<BatchCollegeOptionVO> listAuthorizedColleges(AccountPrincipal actor) {
        AccountPrincipal current = refresh(actor, ADMIN);
        if (isTotalAdmin(current)) {
            List<BatchCollegeOptionVO> all = new ArrayList<BatchCollegeOptionVO>();
            for (CollegeOptionVO college : repository.listActiveColleges()) {
                all.add(new BatchCollegeOptionVO(college.getId(), college.getCode(), college.getName(), true));
            }
            return all;
        }
        Map<Long, BatchCollegeOptionVO> result = new LinkedHashMap<Long, BatchCollegeOptionVO>();
        for (AccountAuthorization authorization : current.getAuthorizations()) {
            String capability = authorization.getCapabilityCode();
            if (!BATCH_MANAGER.equals(capability) && !BATCH_AUDIT.equals(capability)) continue;
            Long collegeId = authorization.getCollegeId();
            if (authorization.getBatchId() != null) {
                Optional<SelectionBatchEntity> batch = repository.findBatch(authorization.getBatchId());
                if (!batch.isPresent() || !collegeId.equals(batch.get().getCollegeId())) continue;
            }
            repository.findActiveCollege(collegeId).ifPresent(college -> {
                BatchCollegeOptionVO previous = result.get(college.getId());
                boolean canCreate = (BATCH_MANAGER.equals(capability) && authorization.getBatchId() == null) ||
                    (previous != null && previous.isCanCreateBatch());
                result.put(college.getId(), new BatchCollegeOptionVO(college.getId(), college.getCode(),
                    college.getName(), canCreate));
            });
        }
        return new ArrayList<BatchCollegeOptionVO>(result.values());
    }

    /** 读取学年目录前先校验学院范围；学院本身已停用时不继续暴露目录。 */
    @Override
    @Transactional(readOnly = true)
    public List<AcademicYearOptionVO> listAcademicYears(AccountPrincipal actor, Long collegeId) {
        AccountPrincipal current = refresh(actor, ADMIN);
        requireAnyManagerScope(current, collegeId);
        requireCollege(collegeId);
        return repository.listAcademicYears();
    }

    /** 管理或只读审计授权可列出其学院范围批次；批次级授权只列授权 ID 白名单。 */
    @Override
    @Transactional(readOnly = true)
    public List<SelectionBatchSummaryVO> listBatches(AccountPrincipal actor, Long collegeId) {
        AccountPrincipal current = refresh(actor, ADMIN);
        requireCollege(collegeId);
        if (isTotalAdmin(current) || authorizationService.hasCapability(current, BATCH_MANAGER, collegeId, null) ||
            authorizationService.hasCapability(current, BATCH_AUDIT, collegeId, null)) {
            return repository.listBatches(collegeId, null);
        }
        List<Long> allowed = new ArrayList<Long>();
        for (AccountAuthorization authorization : current.getAuthorizations()) {
            String capability = authorization.getCapabilityCode();
            if ((!BATCH_MANAGER.equals(capability) && !BATCH_AUDIT.equals(capability)) ||
                !collegeId.equals(authorization.getCollegeId()) || authorization.getBatchId() == null) continue;
            allowed.add(authorization.getBatchId());
        }
        return repository.listBatches(collegeId, allowed);
    }

    /** 先读取批次真实学院/ID 再判断授权；对外统一隐藏无权对象的存在性。 */
    @Override
    @Transactional(readOnly = true)
    public SelectionBatchDetailVO getBatch(AccountPrincipal actor, Long batchId) {
        SelectionBatchEntity batch = requireBatch(batchId);
        requireBatchReader(actor, batch);
        return detail(batchId);
    }

    /**
     * 创建批次草稿。
     *
     * <p>锁定学年行可串行化同学院/学年的并发创建；同一事务内先检查幂等重试，再检查追加原因并写入阶段、
     * 业务操作、生命周期和审计记录。先判断重试是为了让无追加原因的首批创建在响应丢失后仍能安全重放。</p>
     */
    @Override
    @Transactional
    public SelectionBatchDetailVO createBatch(AccountPrincipal actor, CreateSelectionBatchRequest request,
                                               String idempotencyKey) {
        if (request == null) throw invalidArgument("缺少批次创建内容");
        String key = requireIdempotencyKey(idempotencyKey);
        AccountPrincipal current = lockAndRefresh(actor, ADMIN);
        authorizationService.requireCapability(current, BATCH_MANAGER, request.getCollegeId(), null);
        requireCollege(request.getCollegeId());
        if (!repository.lockAcademicYear(request.getAcademicYearId())) throw notFound("学年不存在");
        String code = trim(request.getBatchCode());
        String name = trim(request.getName());
        String appendReason = optionalText(request.getAppendReason());
        if (!code.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,47}")) throw invalidArgument("批次编号格式不正确");
        String fingerprint = fingerprint("CREATE", request.getCollegeId(), request.getAcademicYearId(),
            code, name, request.isSupplementPlanned(), appendReason);
        Optional<OperationRecord> replay = replay(current.getAccountId(), "BATCH_CREATE", key, fingerprint);
        if (replay.isPresent()) return detail(replay.get().getBatchId());
        if (repository.hasPriorBatch(request.getCollegeId(), request.getAcademicYearId()) && appendReason == null) {
            throw invalidArgument("追加批次必须填写创建原因");
        }

        Long batchId;
        try {
            batchId = repository.insertBatch(request.getCollegeId(), request.getAcademicYearId(), code, name,
                request.isSupplementPlanned(), appendReason, current.getAccountId());
        } catch (DuplicateKeyException ex) {
            throw stateConflict("该学院和学年下已经存在相同批次编号");
        }
        repository.insertInitialStages(batchId, request.isSupplementPlanned());
        long operationId = repository.insertOperation(current.getAccountId(), ADMIN, "BATCH_CREATE",
            request.getCollegeId(), batchId, key, fingerprint);
        repository.insertLifecycleEvent(batchId, "CREATE", null, "DRAFT", null,
            current.getAccountId(), ADMIN, appendReason, operationId);
        repository.insertAudit(current.getAccountId(), ADMIN, ADMIN, scopeBasis(request.getCollegeId(), null),
            "SELECTION_BATCH", batchId, "BATCH_CREATE", null,
            snapshot("batchCode", code, "name", name, "supplementPlanned", request.isSupplementPlanned(),
                "appendReason", appendReason), appendReason, operationId);
        repository.completeOperation(operationId);
        return detail(batchId);
    }

    /** 仅允许修改草稿元数据；批次行锁与 If-Match 版本共同阻止并发覆盖。 */
    @Override
    @Transactional
    public SelectionBatchDetailVO updateBatch(AccountPrincipal actor, Long batchId,
        UpdateSelectionBatchRequest request, long expectedVersion) {
        if (request == null) throw invalidArgument("缺少批次修改内容");
        SelectionBatchEntity batch = lockBatch(batchId);
        AccountPrincipal current = requireBatchManager(actor, batch);
        requireVersion(batch.getRowVersion(), expectedVersion);
        if (!"DRAFT".equals(batch.getStatus())) throw stateConflict("只有草稿批次可以修改基本信息");
        String name = trim(request.getName());
        String reason = optionalText(request.getAppendReason());
        String before = snapshot("name", batch.getName(), "appendReason", batch.getAppendReason());
        if (repository.updateBatchMetadataVersion(batchId, expectedVersion, name, reason) != 1) {
            throw preconditionFailed();
        }
        long operationId = repository.insertOperation(current.getAccountId(), ADMIN, "BATCH_METADATA_UPDATE",
            batch.getCollegeId(), batchId, null, null);
        repository.insertAudit(current.getAccountId(), ADMIN, ADMIN, scopeBasis(batch.getCollegeId(), batchId),
            "SELECTION_BATCH", batchId, "BATCH_METADATA_UPDATE", before,
            snapshot("name", name, "appendReason", reason), reason, operationId);
        repository.completeOperation(operationId);
        return detail(batchId);
    }

    /**
     * 保存阶段完整排期。
     *
     * <p>采用整组替换请求，先验证所有阶段顺序，再根据生命周期限制调整范围。没有变化的阶段不新增修订行；
     * 已关闭阶段只能保持原值，开放阶段仅允许在原截止前延长结束时刻。</p>
     */
    @Override
    @Transactional
    public SelectionBatchDetailVO saveSchedule(AccountPrincipal actor, Long batchId,
        BatchScheduleRequest request, long expectedVersion) {
        if (request == null || request.getStages() == null) throw invalidArgument("缺少阶段排期");
        SelectionBatchEntity batch = lockBatch(batchId);
        AccountPrincipal current = requireBatchManager(actor, batch);
        requireVersion(batch.getRowVersion(), expectedVersion);
        if (!Arrays.asList("DRAFT", "SCHEDULED", "ACTIVE").contains(batch.getStatus())) {
            throw stateConflict("当前批次状态不允许修改排期");
        }
        String reason = trim(request.getReason());
        Map<String, SchedulePoint> schedule = normalizeSchedule(request, batch.isSupplementPlanned());
        List<BatchStageVO> oldStages = repository.listStages(batchId);
        Map<String, BatchStageVO> oldByCode = new LinkedHashMap<String, BatchStageVO>();
        for (BatchStageVO stage : oldStages) oldByCode.put(stage.getStageCode(), stage);
        Timestamp now = repository.utcNow();
        if ("DRAFT".equals(batch.getStatus())) {
            for (SchedulePoint point : schedule.values()) {
                if (point.start.isBefore(now.toInstant())) throw invalidArgument("阶段开始时间不能早于当前时间");
            }
        }
        if (!"DRAFT".equals(batch.getStatus())) validateScheduleChanges(oldStages, schedule, now);
        String before = scheduleSnapshot(oldStages);
        int revisionNo = (int) repository.nextScheduleRevision(batchId);
        for (String code : CORE_STAGES) {
            SchedulePoint point = schedule.get(code);
            BatchStageVO old = oldByCode.get(code);
            if (old == null || !sameInstant(old.getPlannedStartAt(), point.start) ||
                !sameInstant(old.getPlannedEndAt(), point.end)) {
                repository.saveStageSchedule(batchId, code, Timestamp.from(point.start), Timestamp.from(point.end),
                    current.getAccountId(), reason, revisionNo++);
            }
        }
        if (batch.isSupplementPlanned()) {
            SchedulePoint point = schedule.get("SUPPLEMENT");
            BatchStageVO old = oldByCode.get("SUPPLEMENT");
            if (old == null || !sameInstant(old.getPlannedStartAt(), point.start) ||
                !sameInstant(old.getPlannedEndAt(), point.end)) {
                repository.saveSupplementSchedule(batchId, Timestamp.from(point.start), Timestamp.from(point.end),
                    current.getAccountId(), reason, revisionNo);
            }
        }
        repository.incrementBatchVersion(batchId);
        long operationId = repository.insertOperation(current.getAccountId(), ADMIN, "BATCH_SCHEDULE_SET",
            batch.getCollegeId(), batchId, null, null);
        repository.insertAudit(current.getAccountId(), ADMIN, ADMIN, scopeBasis(batch.getCollegeId(), batchId),
            "SELECTION_BATCH", batchId, "BATCH_SCHEDULE_SET", before, scheduleSnapshot(schedule), reason, operationId);
        repository.completeOperation(operationId);
        return detail(batchId);
    }

    /**
     * 发布草稿并冻结本次发布所用的规则来源。
     *
     * <p>状态检查、有效导师/名额和专业目录校验、学院/学年运行槽位占用、阶段有效时间复制、规则快照、
     * 生命周期和审计都在同一事务中完成；任一约束失败时整体回滚。</p>
     */
    @Override
    @Transactional
    public SelectionBatchDetailVO publish(AccountPrincipal actor, Long batchId, String idempotencyKey) {
        String key = requireIdempotencyKey(idempotencyKey);
        AccountPrincipal current = lockAndRefresh(actor, ADMIN);
        SelectionBatchEntity batch = lockBatch(batchId);
        requireBatchManager(current, batch);
        String fingerprint = fingerprint("PUBLISH", batchId);
        Optional<OperationRecord> replay = replay(current.getAccountId(), "BATCH_PUBLISH", key, fingerprint);
        if (replay.isPresent()) return detail(batchId);
        if (!"DRAFT".equals(batch.getStatus())) throw stateConflict("只有草稿批次可以发布");
        List<BatchStageVO> stages = repository.listStages(batchId);
        validateStoredSchedule(batch, stages);
        Instant publishNow = repository.utcNow().toInstant();
        for (BatchStageVO stage : stages) {
            if (stage.getPlannedStartAt() != null && stage.getPlannedStartAt().isBefore(publishNow)) {
                throw invalidArgument("阶段开始时间已早于当前时间，请调整排期后再发布");
            }
        }
        int quotas = repository.countConfiguredQuotas(batchId);
        if (quotas == 0 || repository.countEligibleConfiguredQuotas(batchId) == 0) {
            throw new ApiException("BATCH_CONFIGURATION_INCOMPLETE", "请为至少一位当前符合资格的导师设置名额", HttpStatus.CONFLICT);
        }
        if (repository.listActiveMajors(batch.getCollegeId()).isEmpty()) {
            throw new ApiException("BATCH_CONFIGURATION_INCOMPLETE", "专业目录为空，无法发布批次", HttpStatus.CONFLICT);
        }
        long operationId = repository.insertOperation(current.getAccountId(), ADMIN, "BATCH_PUBLISH",
            batch.getCollegeId(), batchId, key, fingerprint);
        try {
            repository.insertRunningSlot(batch.getCollegeId(), batch.getAcademicYearId(), batchId);
        } catch (DuplicateKeyException ex) {
            throw new ApiException("BATCH_RUNNING_SLOT_OCCUPIED", "该学院和学年已有运行中的批次", HttpStatus.CONFLICT);
        }
        repository.publishStages(batchId);
        repository.publishRuleSnapshot(batchId, current.getAccountId());
        if (repository.updateBatchState(batchId, "DRAFT", "SCHEDULED", "PUBLISH") != 1) {
            throw stateConflict("批次状态已变化，请刷新后重试");
        }
        repository.insertLifecycleEvent(batchId, "PUBLISH", "DRAFT", "SCHEDULED", null,
            current.getAccountId(), ADMIN, "批次发布", operationId);
        repository.insertAudit(current.getAccountId(), ADMIN, ADMIN, scopeBasis(batch.getCollegeId(), batchId),
            "SELECTION_BATCH", batchId, "BATCH_PUBLISH", snapshot("status", "DRAFT"),
            snapshot("status", "SCHEDULED"), "批次发布", operationId);
        repository.completeOperation(operationId);
        return detail(batchId);
    }

    /**
     * 启动已发布批次。
     *
     * <p>若数据库 UTC 此时处于填报窗口 [startAt,endAt)，本事务会同步开窗并冻结学生名单及所有关联导师范围；
     * 若窗口尚未开始，则由定时任务在开始时刻执行冻结。若已错过窗口，则按暂缓的 TODO-49 边界拒绝启动。</p>
     */
    @Override
    @Transactional
    public SelectionBatchDetailVO start(AccountPrincipal actor, Long batchId, String idempotencyKey) {
        String key = requireIdempotencyKey(idempotencyKey);
        AccountPrincipal current = lockAndRefresh(actor, ADMIN);
        SelectionBatchEntity batch = lockBatch(batchId);
        requireBatchManager(current, batch);
        String fingerprint = fingerprint("START", batchId);
        Optional<OperationRecord> replay = replay(current.getAccountId(), "BATCH_START", key, fingerprint);
        if (replay.isPresent()) return detail(batchId);
        if (!"SCHEDULED".equals(batch.getStatus())) throw stateConflict("只有已发布批次可以启动");
        List<BatchStageVO> stages = repository.listStages(batchId);
        validateStoredSchedule(batch, stages);
        if (repository.fillingWindowEnded(batchId)) {
            throw new ApiException("BATCH_START_WINDOW_SKIPPED",
                "当前时间已越过填报窗口；本期暂不处理跳过填报阶段时的名单和范围冻结", HttpStatus.CONFLICT);
        }
        long operationId = repository.insertOperation(current.getAccountId(), ADMIN, "BATCH_START",
            batch.getCollegeId(), batchId, key, fingerprint);
        if (repository.updateBatchState(batchId, "SCHEDULED", "ACTIVE", "START") != 1) {
            throw stateConflict("批次状态已变化，请刷新后重试");
        }
        boolean fillingOpen = repository.fillingWindowOpen(batchId);
        repository.updateBatchStage(batchId, "FILLING", fillingOpen ? "OPEN" : "WAITING_FILLING", true);
        repository.insertLifecycleEvent(batchId, "START", "SCHEDULED", "ACTIVE",
            repository.stageId(batchId, "FILLING"), current.getAccountId(), ADMIN, "管理员启动批次", operationId);
        repository.insertAudit(current.getAccountId(), ADMIN, ADMIN, scopeBasis(batch.getCollegeId(), batchId),
            "SELECTION_BATCH", batchId, "BATCH_START", snapshot("status", "SCHEDULED"),
            snapshot("status", "ACTIVE", "currentStage", "FILLING"), "管理员启动批次", operationId);
        if (fillingOpen) freezeRosterAndScopes(batch, current.getAccountId());
        repository.completeOperation(operationId);
        return detail(batchId);
    }

    /** 暂停活动批次；状态行锁同时阻止新的学生/导师办理进入该批次。 */
    @Override
    @Transactional
    public SelectionBatchDetailVO pause(AccountPrincipal actor, Long batchId, String idempotencyKey) {
        String key = requireIdempotencyKey(idempotencyKey);
        AccountPrincipal current = lockAndRefresh(actor, ADMIN);
        SelectionBatchEntity batch = lockBatch(batchId);
        requireBatchManager(current, batch);
        String fingerprint = fingerprint("PAUSE", batchId);
        if (replay(current.getAccountId(), "BATCH_PAUSE", key, fingerprint).isPresent()) return detail(batchId);
        if (!"ACTIVE".equals(batch.getStatus())) throw stateConflict("只有进行中的批次可以暂停");

        List<BatchStageVO> stages = repository.listStages(batchId);
        String frozenStage = null;
        Long stageId = repository.currentStageId(batchId);
        if (stageId != null) {
            for (BatchStageVO stage : stages) {
                if (stageId.equals(stage.getId())) { frozenStage = stage.getStageCode(); break; }
            }
        }
        long operationId = repository.insertOperation(current.getAccountId(), ADMIN, "BATCH_PAUSE",
            batch.getCollegeId(), batchId, key, fingerprint);
        if (repository.updateBatchState(batchId, "ACTIVE", "PAUSED", "PAUSE") != 1) {
            throw stateConflict("批次状态已变化，请刷新后重试");
        }
        repository.insertPauseLifecycleEvent(batchId, stageId, frozenStage, current.getAccountId(),
            "管理员暂停批次", operationId);
        repository.insertAudit(current.getAccountId(), ADMIN, ADMIN, scopeBasis(batch.getCollegeId(), batchId),
            "SELECTION_BATCH", batchId, "BATCH_PAUSE", snapshot("status", "ACTIVE", "currentStage", frozenStage),
            snapshot("status", "PAUSED", "currentStage", frozenStage), "管理员暂停批次", operationId);
        repository.completeOperation(operationId);
        return detail(batchId);
    }

    /** 恢复批次；未关闭阶段和补选窗口按暂停周期长度整体平移后再恢复办理。 */
    @Override
    @Transactional
    public SelectionBatchDetailVO resume(AccountPrincipal actor, Long batchId, String idempotencyKey) {
        String key = requireIdempotencyKey(idempotencyKey);
        AccountPrincipal current = lockAndRefresh(actor, ADMIN);
        SelectionBatchEntity batch = lockBatch(batchId);
        requireBatchManager(current, batch);
        String fingerprint = fingerprint("RESUME", batchId);
        if (replay(current.getAccountId(), "BATCH_RESUME", key, fingerprint).isPresent()) return detail(batchId);
        if (!"PAUSED".equals(batch.getStatus())) throw stateConflict("只有已暂停的批次可以恢复");
        PauseEvent pause = repository.lockOpenPauseEvent(batchId)
            .orElseThrow(() -> stateConflict("批次暂停记录不完整，无法安全恢复排期"));

        long operationId = repository.insertOperation(current.getAccountId(), ADMIN, "BATCH_RESUME",
            batch.getCollegeId(), batchId, key, fingerprint);
        if (repository.finishPauseAndShiftSchedule(batchId, pause.getId()) != 1) {
            throw stateConflict("批次暂停周期已变化，请刷新后重试");
        }
        if (repository.updateBatchState(batchId, "PAUSED", "ACTIVE", "RESUME") != 1) {
            throw stateConflict("批次状态已变化，请刷新后重试");
        }
        long shiftedMillis = Math.max(0L, repository.utcNow().getTime() - pause.getStartedAt().getTime());
        String reason = "管理员恢复批次，未关闭阶段和补选窗口按暂停时长顺延";
        repository.insertLifecycleEvent(batchId, "RESUME", "PAUSED", "ACTIVE", repository.currentStageId(batchId),
            current.getAccountId(), ADMIN, reason, operationId);
        repository.insertAudit(current.getAccountId(), ADMIN, ADMIN, scopeBasis(batch.getCollegeId(), batchId),
            "SELECTION_BATCH", batchId, "BATCH_RESUME", snapshot("status", "PAUSED"),
            snapshot("status", "ACTIVE", "scheduleShiftMillis", shiftedMillis), reason, operationId);
        repository.completeOperation(operationId);
        return detail(batchId);
    }

    /** 取消草稿、已发布、进行中或暂停批次；终态批次必须保持原状。 */
    @Override
    @Transactional
    public SelectionBatchDetailVO cancel(AccountPrincipal actor, Long batchId, String idempotencyKey) {
        String key = requireIdempotencyKey(idempotencyKey);
        AccountPrincipal current = lockAndRefresh(actor, ADMIN);
        SelectionBatchEntity batch = lockBatch(batchId);
        requireBatchManager(current, batch);
        String fingerprint = fingerprint("CANCEL", batchId);
        if (replay(current.getAccountId(), "BATCH_CANCEL", key, fingerprint).isPresent()) return detail(batchId);
        String oldStatus = batch.getStatus();
        if (!Arrays.asList("DRAFT", "SCHEDULED", "ACTIVE", "PAUSED").contains(oldStatus)) {
            throw stateConflict("只有尚未结束的批次可以取消");
        }

        long operationId = repository.insertOperation(current.getAccountId(), ADMIN, "BATCH_CANCEL",
            batch.getCollegeId(), batchId, key, fingerprint);
        repository.cancelPendingApplications(batchId, current.getAccountId(), operationId);
        repository.markUnmatchedByBatchCancellation(batchId, operationId);
        repository.closeRemainingBatchStages(batchId);
        repository.releaseRunningSlot(batchId);
        if (repository.updateBatchState(batchId, oldStatus, "CANCELLED", "CANCEL") != 1) {
            throw stateConflict("批次状态已变化，请刷新后重试");
        }
        String reason = "管理员取消批次";
        repository.insertLifecycleEvent(batchId, "CANCEL", oldStatus, "CANCELLED", repository.currentStageId(batchId),
            current.getAccountId(), ADMIN, reason, operationId);
        repository.insertAudit(current.getAccountId(), ADMIN, ADMIN, scopeBasis(batch.getCollegeId(), batchId),
            "SELECTION_BATCH", batchId, "BATCH_CANCEL", snapshot("status", oldStatus),
            snapshot("status", "CANCELLED"), reason, operationId);
        repository.completeOperation(operationId);
        return detail(batchId);
    }

    /** 仅归档完成的批次；归档后业务资料保持只读。 */
    @Override
    @Transactional
    public SelectionBatchDetailVO archive(AccountPrincipal actor, Long batchId, String idempotencyKey) {
        return changeTerminalArchiveState(actor, batchId, idempotencyKey, false);
    }

    /** 解除归档回到 COMPLETED，供关系纠错等已授权审计操作使用。 */
    @Override
    @Transactional
    public SelectionBatchDetailVO unarchive(AccountPrincipal actor, Long batchId, String idempotencyKey) {
        return changeTerminalArchiveState(actor, batchId, idempotencyKey, true);
    }

    /** 仅在目标轮次仍开放时延期；后续阶段按新增时长顺延并逐阶段保存修订。 */
    @Override
    @Transactional
    public SelectionBatchDetailVO extendRound(AccountPrincipal actor, Long batchId, int roundNo,
        ExtendRoundRequest request, long expectedVersion, String idempotencyKey) {
        if (roundNo < 1 || roundNo > 3) throw invalidArgument("常规轮次只能是 1 至 3");
        if (request == null) throw invalidArgument("缺少轮次延期信息");
        String key = requireIdempotencyKey(idempotencyKey);
        AccountPrincipal current = lockAndRefresh(actor, ADMIN);
        SelectionBatchEntity batch = lockBatch(batchId);
        requireBatchManager(current, batch);
        requireVersion(batch.getRowVersion(), expectedVersion);
        String action = "BATCH_ROUND_EXTEND";
        Instant newEnd = parseUtc(request.getNewEndAt());
        String reason = trim(request.getReason());
        String fingerprint = fingerprint("ROUND_EXTEND", batchId, roundNo, newEnd, reason, expectedVersion);
        if (replay(current.getAccountId(), action, key, fingerprint).isPresent()) return detail(batchId);
        if (!("ACTIVE".equals(batch.getStatus()) || "PAUSED".equals(batch.getStatus()))) {
            throw stateConflict("当前批次状态不允许延期轮次");
        }
        List<BatchStageVO> stages = repository.listStages(batchId);
        String code = "ROUND_" + roundNo;
        BatchStageVO target = null;
        for (BatchStageVO stage : stages) if (code.equals(stage.getStageCode())) target = stage;
        if (target == null || !"OPEN".equals(target.getStatus()) || target.getPlannedEndAt() == null ||
            target.getPlannedStartAt() == null) throw stateConflict("只有尚未关闭的开放轮次可以延期");
        Instant now = repository.utcNow().toInstant();
        if (!target.getPlannedEndAt().isAfter(now) || !newEnd.isAfter(target.getPlannedEndAt())) {
            throw stateConflict("轮次截止前只能将截止时间延长到更晚时刻");
        }
        Duration delta = Duration.between(target.getPlannedEndAt(), newEnd);
        Map<String, SchedulePoint> before = schedulePoints(stages);
        Map<String, SchedulePoint> after = new LinkedHashMap<String, SchedulePoint>(before);
        after.put(code, new SchedulePoint(target.getPlannedStartAt(), newEnd));
        for (BatchStageVO stage : stages) {
            if (stage.getStageOrder() <= target.getStageOrder() || stage.getPlannedStartAt() == null ||
                stage.getPlannedEndAt() == null) continue;
            after.put(stage.getStageCode(), new SchedulePoint(stage.getPlannedStartAt().plus(delta),
                stage.getPlannedEndAt().plus(delta)));
        }
        validatePointOrder(after);
        long operationId = repository.insertOperation(current.getAccountId(), ADMIN, action,
            batch.getCollegeId(), batchId, key, fingerprint);
        int revision = (int) repository.nextScheduleRevision(batchId);
        for (BatchStageVO stage : stages) {
            SchedulePoint old = before.get(stage.getStageCode());
            SchedulePoint next = after.get(stage.getStageCode());
            if (old == null || next == null || old.start.equals(next.start) && old.end.equals(next.end)) continue;
            if ("SUPPLEMENT".equals(stage.getStageCode())) {
                repository.saveSupplementSchedule(batchId, Timestamp.from(next.start), Timestamp.from(next.end),
                    current.getAccountId(), reason, revision++);
            } else {
                repository.saveStageSchedule(batchId, stage.getStageCode(), Timestamp.from(next.start),
                    Timestamp.from(next.end), current.getAccountId(), reason, revision++);
            }
        }
        repository.incrementBatchVersion(batchId);
        repository.insertAudit(current.getAccountId(), ADMIN, ADMIN, scopeBasis(batch.getCollegeId(), batchId),
            "BATCH_STAGE", target.getId(), action, scheduleSnapshot(before), scheduleSnapshot(after), reason, operationId);
        repository.completeOperation(operationId);
        return detail(batchId);
    }

    /** 只重开因截止时间关闭的轮次；下游待处理项取代后按明确新截止时间重排。 */
    @Override
    @Transactional
    public SelectionBatchDetailVO reopenRound(AccountPrincipal actor, Long batchId, int roundNo,
        ReopenRoundRequest request, long expectedVersion, String idempotencyKey) {
        if (roundNo < 1 || roundNo > 3) throw invalidArgument("常规轮次只能是 1 至 3");
        if (request == null) throw invalidArgument("缺少轮次重开信息");
        String key = requireIdempotencyKey(idempotencyKey);
        AccountPrincipal current = lockAndRefresh(actor, ADMIN);
        SelectionBatchEntity batch = lockBatch(batchId);
        requireBatchManager(current, batch);
        String action = "BATCH_ROUND_REOPEN";
        Instant newEnd = parseUtc(request.getNewEndAt());
        String reason = trim(request.getReason());
        String fingerprint = fingerprint("ROUND_REOPEN", batchId, roundNo, newEnd, reason, expectedVersion);
        if (replay(current.getAccountId(), action, key, fingerprint).isPresent()) return detail(batchId);
        requireVersion(batch.getRowVersion(), expectedVersion);
        if (!"PAUSED".equals(batch.getStatus())) throw stateConflict("只有已暂停的批次可以重开轮次");
        SelectionBatchRepository.PauseEvent pause = repository.lockOpenPauseEvent(batchId)
            .orElseThrow(() -> stateConflict("批次暂停记录不完整，无法安全重开轮次"));

        List<BatchStageVO> stages = repository.listStages(batchId);
        String code = "ROUND_" + roundNo;
        BatchStageVO target = findStage(stages, code);
        if (target == null || !"CLOSED".equals(target.getStatus()) || target.getPlannedStartAt() == null ||
            target.getPlannedEndAt() == null || !("ROUND_" + roundNo + "_DEADLINE").equals(repository.stageCloseReason(target.getId()))) {
            throw stateConflict("只有按截止时间关闭的常规轮次可以重开");
        }
        Timestamp dbNow = repository.utcNow();
        Instant now = dbNow.toInstant();
        if (!newEnd.isAfter(now)) throw stateConflict("新的截止时间必须晚于当前数据库时间");
        int oldCycle = repository.stageExecutionCycle(target.getId());
        int newCycle = oldCycle + 1;
        int recoverable = repository.countRecoverableDeadlineApplications(target.getId(), roundNo);
        if (recoverable == 0) throw stateConflict("没有仍可恢复的系统自动结案申请");

        BatchStageVO nextRound = roundNo < 3 ? findStage(stages, "ROUND_" + (roundNo + 1)) : null;
        int nextRoundCycle = 0;
        if (nextRound != null) {
            if (!("NOT_STARTED".equals(nextRound.getStatus()) || "OPEN".equals(nextRound.getStatus()))) {
                throw stateConflict("下一轮已关闭，不能回滚后续处理");
            }
            nextRoundCycle = repository.stageExecutionCycle(nextRound.getId());
            if ("OPEN".equals(nextRound.getStatus()) &&
                repository.countRoundApplicationsOutsideReview(nextRound.getId(), nextRoundCycle) > 0) {
                throw stateConflict("下一轮已有导师处理动作，不能重开前一轮");
            }
            if ("NOT_STARTED".equals(nextRound.getStatus()) && repository.countRoundApplications(nextRound.getId(), nextRoundCycle) > 0) {
                throw stateConflict("下一轮已有处理记录，不能重开前一轮");
            }
        } else if (batch.isSupplementPlanned()) {
            BatchStageVO supplement = findStage(stages, "SUPPLEMENT");
            if (supplement == null || !"NOT_STARTED".equals(supplement.getStatus()) ||
                !"PLANNED".equals(repository.supplementWindowStatus(batchId)) || repository.countSupplementApplications(batchId) > 0) {
                throw stateConflict("第三轮重开前，补选阶段必须尚未开放且没有申请");
            }
        }

        Duration delta = Duration.between(target.getPlannedEndAt(), newEnd);
        Map<String, SchedulePoint> before = schedulePoints(stages);
        Map<String, SchedulePoint> after = new LinkedHashMap<String, SchedulePoint>(before);
        after.put(code, new SchedulePoint(now, newEnd));
        for (BatchStageVO stage : stages) {
            if (stage.getStageOrder() <= target.getStageOrder() || stage.getPlannedStartAt() == null ||
                stage.getPlannedEndAt() == null) continue;
            after.put(stage.getStageCode(), new SchedulePoint(stage.getPlannedStartAt().plus(delta),
                stage.getPlannedEndAt().plus(delta)));
        }
        validatePointOrder(after);

        long operationId = repository.insertOperation(current.getAccountId(), ADMIN, action,
            batch.getCollegeId(), batchId, key, fingerprint);
        int revision = (int) repository.nextScheduleRevision(batchId);
        for (BatchStageVO stage : stages) {
            SchedulePoint old = before.get(stage.getStageCode());
            SchedulePoint next = after.get(stage.getStageCode());
            if (old == null || next == null || old.start.equals(next.start) && old.end.equals(next.end)) continue;
            if ("SUPPLEMENT".equals(stage.getStageCode())) {
                repository.saveSupplementSchedule(batchId, Timestamp.from(next.start), Timestamp.from(next.end),
                    current.getAccountId(), reason, revision++);
            } else {
                repository.saveStageSchedule(batchId, stage.getStageCode(), Timestamp.from(next.start),
                    Timestamp.from(next.end), current.getAccountId(), reason, revision++);
            }
        }
        if (repository.reopenStage(target.getId(), dbNow, Timestamp.from(newEnd)) != 1) {
            throw stateConflict("轮次状态已变化，请刷新后重试");
        }
        int reopened = repository.reopenDeadlineApplications(batchId, target.getId(), roundNo, newCycle,
            current.getAccountId(), Long.valueOf(operationId));
        if (reopened != recoverable) throw stateConflict("可恢复申请已变化，请刷新后重试");
        if (nextRound != null) {
            repository.supersedeUntouchedRound(nextRound.getId(), nextRoundCycle, current.getAccountId(), Long.valueOf(operationId));
        }
        repository.setCurrentStageForReopen(batchId, target.getId());
        repository.restartPauseClockForReopen(batchId, pause.getId(), target.getId(), code,
            current.getAccountId(), reason, Long.valueOf(operationId));
        repository.insertLifecycleEvent(batchId, "ROUND_" + roundNo + "_REOPENED", "PAUSED", "PAUSED",
            target.getId(), current.getAccountId(), ADMIN, reason, Long.valueOf(operationId));
        repository.insertAudit(current.getAccountId(), ADMIN, ADMIN, scopeBasis(batch.getCollegeId(), batchId),
            "BATCH_STAGE", target.getId(), action, scheduleSnapshot(before), scheduleSnapshot(after), reason,
            Long.valueOf(operationId));
        repository.completeOperation(operationId);
        return detail(batchId);
    }

    private BatchStageVO findStage(List<BatchStageVO> stages, String code) {
        for (BatchStageVO stage : stages) if (code.equals(stage.getStageCode())) return stage;
        return null;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SupplementTeacherVO> listSupplementTeachers(AccountPrincipal actor, Long batchId) {
        SelectionBatchEntity batch = requireBatch(batchId);
        requireBatchReader(actor, batch);
        if (!batch.isSupplementPlanned()) throw stateConflict("该批次未安排补选窗口");
        return supplementTeacherViews(batch);
    }

    @Override
    @Transactional
    public List<SupplementTeacherVO> setSupplementTeachers(AccountPrincipal actor, Long batchId,
        SetSupplementTeachersRequest request, long expectedVersion, String idempotencyKey) {
        if (request == null || request.getTeacherIds() == null) throw invalidArgument("缺少补选导师名单");
        if (request.getTeacherIds().size() != new HashSet<Long>(request.getTeacherIds()).size()) {
            throw invalidArgument("补选导师名单不能重复");
        }
        String key = requireIdempotencyKey(idempotencyKey);
        AccountPrincipal current = lockAndRefresh(actor, ADMIN);
        SelectionBatchEntity batch = lockBatch(batchId);
        requireBatchManager(current, batch);
        requireVersion(batch.getRowVersion(), expectedVersion);
        if (!batch.isSupplementPlanned()) throw stateConflict("该批次未安排补选窗口");
        if (!("DRAFT".equals(batch.getStatus()) || "SCHEDULED".equals(batch.getStatus()) ||
            "ACTIVE".equals(batch.getStatus()) || "PAUSED".equals(batch.getStatus()))) {
            throw stateConflict("当前批次状态不允许调整补选导师");
        }
        String windowStatus = repository.supplementWindowStatus(batchId);
        if (windowStatus == null) throw stateConflict("请先配置补选窗口排期");
        if ("CLOSED".equals(windowStatus) || "NOT_SCHEDULED".equals(windowStatus)) {
            throw stateConflict("补选窗口关闭后不能调整导师名单");
        }
        Set<Long> desired = new LinkedHashSet<Long>();
        for (Long teacherId : request.getTeacherIds()) {
            if (teacherId == null || teacherId.longValue() <= 0) throw invalidArgument("导师 ID 必须为正数");
            desired.add(teacherId);
        }
        List<BatchTeacherQuotaVO> eligibleRows = repository.listTeacherQuotas(batchId, batch.getCollegeId(), batch.getAcademicYearId());
        Set<Long> eligible = new HashSet<Long>();
        for (BatchTeacherQuotaVO row : eligibleRows) if (row.getQuotaLimit() != null) eligible.add(row.getTeacherId());
        if (!eligible.containsAll(desired)) throw invalidArgument("名单包含不属于本批次有效导师目录的导师");
        String reason = trim(request.getReason());
        List<Long> sortedIds = new ArrayList<Long>(desired);
        Collections.sort(sortedIds);
        String fingerprint = fingerprint("SUPPLEMENT_TEACHERS", batchId, sortedIds, reason, expectedVersion);
        if (replay(current.getAccountId(), "BATCH_SUPPLEMENT_TEACHERS_SET", key, fingerprint).isPresent()) {
            return supplementTeacherViews(batch);
        }
        Map<Long, Integer> before = repository.currentSupplementTeacherPermissions(batchId);
        long operationId = repository.insertOperation(current.getAccountId(), ADMIN,
            "BATCH_SUPPLEMENT_TEACHERS_SET", batch.getCollegeId(), batchId, key, fingerprint);
        repository.replaceSupplementTeacherPermissions(batchId, desired, current.getAccountId(), reason);
        repository.incrementBatchVersion(batchId);
        Map<Long, Integer> after = repository.currentSupplementTeacherPermissions(batchId);
        repository.insertAudit(current.getAccountId(), ADMIN, ADMIN, scopeBasis(batch.getCollegeId(), batchId),
            "SUPPLEMENT_TEACHER_LIST", batchId, "BATCH_SUPPLEMENT_TEACHERS_SET", snapshot("teachers", before),
            snapshot("teachers", after), reason, operationId);
        repository.completeOperation(operationId);
        return supplementTeacherViews(batch);
    }

    private List<SupplementTeacherVO> supplementTeacherViews(SelectionBatchEntity batch) {
        Map<Long, Integer> permissions = repository.currentSupplementTeacherPermissions(batch.getId());
        List<BatchTeacherQuotaVO> quotas = repository.listTeacherQuotas(batch.getId(), batch.getCollegeId(), batch.getAcademicYearId());
        List<SupplementTeacherVO> result = new ArrayList<SupplementTeacherVO>();
        for (BatchTeacherQuotaVO quota : quotas) {
            Integer version = permissions.get(quota.getTeacherId());
            result.add(new SupplementTeacherVO(quota.getTeacherId(), quota.getEmployeeNo(), quota.getFullName(),
                version != null, version, quota.getQuotaLimit(), quota.getOccupiedCount(), quota.getRemainingCount()));
        }
        return result;
    }

    private Map<String, SchedulePoint> schedulePoints(List<BatchStageVO> stages) {
        Map<String, SchedulePoint> values = new LinkedHashMap<String, SchedulePoint>();
        for (BatchStageVO stage : stages) if (stage.getPlannedStartAt() != null && stage.getPlannedEndAt() != null) {
            values.put(stage.getStageCode(), new SchedulePoint(stage.getPlannedStartAt(), stage.getPlannedEndAt()));
        }
        return values;
    }

    private void validatePointOrder(Map<String, SchedulePoint> points) {
        Instant end = null;
        List<String> codes = Arrays.asList("FILLING", "ROUND_1", "ROUND_2", "ROUND_3", "SUPPLEMENT");
        for (String code : codes) {
            SchedulePoint point = points.get(code);
            if (point == null) continue;
            if (!point.start.isBefore(point.end) || end != null && point.start.isBefore(end)) {
                throw stateConflict("延期后阶段时间发生重叠，请先调整排期");
            }
            end = point.end;
        }
    }

    private SelectionBatchDetailVO changeTerminalArchiveState(AccountPrincipal actor, Long batchId,
        String idempotencyKey, boolean unarchive) {
        String key = requireIdempotencyKey(idempotencyKey);
        AccountPrincipal current = lockAndRefresh(actor, ADMIN);
        SelectionBatchEntity batch = lockBatch(batchId);
        requireBatchManager(current, batch);
        String action = unarchive ? "UNARCHIVE" : "ARCHIVE";
        String operationAction = "BATCH_" + action;
        String fingerprint = fingerprint(action, batchId);
        if (replay(current.getAccountId(), operationAction, key, fingerprint).isPresent()) return detail(batchId);
        String oldStatus = batch.getStatus();
        String expectedStatus = unarchive ? "ARCHIVED" : "COMPLETED";
        String nextStatus = unarchive ? "COMPLETED" : "ARCHIVED";
        if (!expectedStatus.equals(oldStatus)) {
            throw stateConflict(unarchive ? "只有已归档批次可以解除归档" : "只有已完成批次可以归档");
        }
        long operationId = repository.insertOperation(current.getAccountId(), ADMIN, operationAction,
            batch.getCollegeId(), batchId, key, fingerprint);
        if (repository.updateBatchState(batchId, oldStatus, nextStatus, action) != 1) {
            throw stateConflict("批次状态已变化，请刷新后重试");
        }
        String reason = unarchive ? "管理员解除批次归档" : "管理员归档批次";
        repository.insertLifecycleEvent(batchId, action, oldStatus, nextStatus, null,
            current.getAccountId(), ADMIN, reason, operationId);
        repository.insertAudit(current.getAccountId(), ADMIN, ADMIN, scopeBasis(batch.getCollegeId(), batchId),
            "SELECTION_BATCH", batchId, operationAction, snapshot("status", oldStatus),
            snapshot("status", nextStatus), reason, operationId);
        repository.completeOperation(operationId);
        return detail(batchId);
    }

    /** 返回此批次学院已启用专业目录，写入范围时仍由服务端再次校验专业归属。 */
    @Override
    @Transactional(readOnly = true)
    public List<MajorVO> listBatchMajors(AccountPrincipal actor, Long batchId) {
        SelectionBatchEntity batch = requireBatch(batchId);
        requireBatchReader(actor, batch);
        return repository.listActiveMajors(batch.getCollegeId());
    }

    /** 返回符合当前导师目录条件的名额行，并附带范围是否已配置/冻结的展示状态。 */
    @Override
    @Transactional(readOnly = true)
    public List<BatchTeacherQuotaVO> listTeacherQuotas(AccountPrincipal actor, Long batchId) {
        SelectionBatchEntity batch = requireBatch(batchId);
        requireBatchReader(actor, batch);
        return repository.listTeacherQuotas(batchId, batch.getCollegeId(), batch.getAcademicYearId());
    }

    /** 统计只读能力可查看获授批次；总管理员仍受批次状态和统计分母规则约束。 */
    @Override
    @Transactional(readOnly = true)
    public BatchStatisticsVO statistics(AccountPrincipal actor, Long batchId) {
        SelectionBatchEntity batch = requireBatch(batchId);
        requireBatchReader(actor, batch);
        return repository.findBatchStatistics(batch);
    }

    /**
     * 创建或更新单名导师名额。
     *
     * <p>先锁定批次，再读取导师资格依据并锁定名额行；已有名额按版本更新，新建名额要求版本 0。
     * 幂等重放不重复写流水，数据库行版本和占用数检查确保不覆盖并发更新，也不把上限降到已录取人数以下。</p>
     */
    @Override
    @Transactional
    public BatchTeacherQuotaVO setTeacherQuota(AccountPrincipal actor, Long batchId, Long teacherId,
        SetTeacherQuotaRequest request, long expectedVersion, String idempotencyKey) {
        if (request == null || request.getQuotaLimit() == null || request.getQuotaLimit().intValue() < 0) {
            throw invalidArgument("名额上限必须是大于等于 0 的整数");
        }
        String key = requireIdempotencyKey(idempotencyKey);
        AccountPrincipal current = lockAndRefresh(actor, ADMIN);
        SelectionBatchEntity batch = lockBatch(batchId);
        requireBatchManager(current, batch);
        if (!Arrays.asList("DRAFT", "SCHEDULED", "ACTIVE", "PAUSED").contains(batch.getStatus())) {
            throw stateConflict("当前批次状态不允许调整导师名额");
        }
        String basis = repository.teacherEligibilityBasis(batchId, teacherId)
            .orElseThrow(() -> new ApiException("NOT_FOUND", "导师不在本批次有效导师目录中", HttpStatus.NOT_FOUND));
        int newLimit = request.getQuotaLimit().intValue();
        String fingerprint = fingerprint("QUOTA", batchId, teacherId, newLimit, expectedVersion);
        Optional<OperationRecord> replay = repository.findQuotaOperation(current.getAccountId(), key);
        if (replay.isPresent()) {
            requireSameFingerprint(replay.get(), fingerprint);
            TeacherQuotaEntity prior = repository.lockQuota(batchId, teacherId)
                .orElseThrow(() -> stateConflict("名额操作结果已无法恢复"));
            return repository.findTeacherQuotaView(batchId, batch.getCollegeId(), batch.getAcademicYearId(), teacherId)
                .orElseThrow(() -> stateConflict("名额操作结果已无法恢复"));
        }
        Optional<TeacherQuotaEntity> existing = repository.lockQuota(batchId, teacherId);
        if (existing.isPresent()) {
            TeacherQuotaEntity quota = existing.get();
            requireVersion(quota.getRowVersion(), expectedVersion);
            if (newLimit < quota.getOccupiedCount()) {
                throw new ApiException("QUOTA_BELOW_OCCUPIED", "名额上限不能低于已锁定关系人数", HttpStatus.CONFLICT);
            }
            if (!repository.updateQuota(batchId, teacherId, expectedVersion, basis, newLimit)) throw preconditionFailed();
        } else {
            if (expectedVersion != 0) throw preconditionFailed();
            try { repository.insertQuota(batchId, teacherId, basis, newLimit); }
            catch (DuplicateKeyException ex) { throw stateConflict("名额账户已被其他请求创建"); }
        }
        long operationId = repository.insertOperation(current.getAccountId(), ADMIN, "BATCH_TEACHER_QUOTA_SET",
            batch.getCollegeId(), batchId, key, fingerprint);
        repository.insertAudit(current.getAccountId(), ADMIN, ADMIN, scopeBasis(batch.getCollegeId(), batchId),
            "BATCH_TEACHER_QUOTA", teacherId, "BATCH_TEACHER_QUOTA_SET",
            existing.map(q -> snapshot("quotaLimit", q.getQuotaLimit(), "occupiedCount", q.getOccupiedCount())).orElse(null),
            snapshot("quotaLimit", newLimit, "eligibilityBasis", basis), null, operationId);
        repository.completeOperation(operationId);
        TeacherQuotaEntity updated = repository.lockQuota(batchId, teacherId)
            .orElseThrow(() -> new IllegalStateException("Quota row missing after update"));
        return repository.findTeacherQuotaView(batchId, batch.getCollegeId(), batch.getAcademicYearId(), teacherId)
            .orElseThrow(() -> new IllegalStateException("Quota row missing after update"));
    }

    /** 只为认证主体关联的导师身份列出名额批次，不接受客户端传入 teacherId。 */
    @Override
    @Transactional(readOnly = true)
    public List<TeacherScopeBatchOptionVO> listTeacherScopeBatches(AccountPrincipal actor) {
        AccountPrincipal current = refresh(actor, TEACHER);
        if (current.getIdentity() == null || current.getIdentity().getId() == null) throw forbidden("导师身份记录不存在");
        return repository.listTeacherScopeBatches(current.getIdentity().getId());
    }

    /** 读取本人范围；没有保存版本时返回“全部学位类型和全部启用专业”的预览，不在读取时写库。 */
    @Override
    @Transactional(readOnly = true)
    public TeacherApplicationScopeVO getTeacherScope(AccountPrincipal actor, Long batchId) {
        AccountPrincipal current = refresh(actor, TEACHER);
        SelectionBatchEntity batch = requireBatch(batchId);
        Long teacherId = requireTeacherIdentity(current);
        requireTeacherBatch(batchId, teacherId);
        return teacherScopeView(batch, teacherId);
    }

    /**
     * 保存导师本人招生范围的新版本。
     *
     * <p>账号与批次行锁用于和账号状态变更、管理员名额配置及填报开窗串行；先检查该导师确实属于批次，再比较 If-Match
     * 版本并校验目录。新版本、专业快照、业务操作及审计同事务提交，冻结后不允许创建后续版本。</p>
     */
    @Override
    @Transactional
    public TeacherApplicationScopeVO setTeacherScope(AccountPrincipal actor, Long batchId,
        SetTeacherApplicationScopeRequest request, int expectedVersion) {
        if (request == null) throw invalidArgument("缺少导师范围配置");
        AccountPrincipal current = lockAndRefresh(actor, TEACHER);
        SelectionBatchEntity batch = lockBatch(batchId);
        Long teacherId = requireTeacherIdentity(current);
        requireTeacherBatch(batchId, teacherId);
        if (!repository.scopeEditTimeOpen(batchId)) {
            throw stateConflict("填报窗口开始后导师可报范围已冻结");
        }
        Optional<TeacherScopeVersionEntity> latest = repository.latestScope(batchId, teacherId, true);
        int currentVersion = latest.map(TeacherScopeVersionEntity::getVersionNo).orElse(0);
        if (currentVersion != expectedVersion) throw preconditionFailed();
        ScopeSelection selection = normalizeScope(request, batch.getCollegeId());
        String before = latest.map(scope -> snapshot("versionNo", scope.getVersionNo(),
            "allowedDegreeMask", scope.getAllowedDegreeMask(), "majorIds", repository.listScopeMajorIds(scope.getId())))
            .orElse(null);
        int nextVersion = currentVersion + 1;
        Long scopeId;
        try {
            scopeId = repository.insertScopeVersion(batchId, teacherId, nextVersion, selection.degreeMask,
                current.getAccountId(), selection.defaultAll ? "DEFAULT_ALL" : "TEACHER", selection.defaultAll);
            repository.insertAllowedMajors(scopeId, selection.majors);
        } catch (DuplicateKeyException ex) {
            throw stateConflict("导师范围版本已变化，请刷新后重试");
        }
        repository.insertScopeAudit(current.getAccountId(), TEACHER, batchId, scopeId, before,
            snapshot("versionNo", nextVersion, "allowedDegreeTypes", selection.degreeTypes,
                "majorIds", selection.majorIds, "defaultAllApplied", selection.defaultAll));
        return teacherScopeView(batch, teacherId);
    }

    /**
     * 定时扫描活动批次；每批单独提交，异常时该批回滚并等待下一次调度重试。
     * 数据库 UTC 到达填报开始时，以批次行锁串行冻结学生分母与导师范围。
     */
    @Override
    public void openDueFillingWindows() {
        for (Long batchId : repository.listActiveBatchIds()) {
            try {
                transactionTemplate.execute(status -> {
                    openDueFillingWindow(batchId);
                    return null;
                });
            } catch (RuntimeException ex) {
                // 一批次失败不阻断其他批次；事务已回滚，下一次调度会重试同一冻结动作。
                org.slf4j.LoggerFactory.getLogger(getClass()).warn(
                    "Could not open the filling window for batch {}", batchId, ex);
            }
        }
    }

    /** 在已锁定的批次行上再次确认状态与数据库时间，避免多实例调度重复冻结。 */
    private void openDueFillingWindow(Long batchId) {
        SelectionBatchEntity batch = repository.lockBatch(batchId).orElse(null);
        if (batch == null || !"ACTIVE".equals(batch.getStatus()) || batch.getFrozenRosterAt() != null ||
            !repository.fillingWindowDue(batchId)) return;
        repository.updateBatchStage(batchId, "FILLING", "OPEN", true);
        freezeRosterAndScopes(batch, null);
        repository.insertLifecycleEvent(batchId, "FILLING_OPEN", "ACTIVE", "ACTIVE",
            repository.stageId(batchId, "FILLING"), null, "SYSTEM", "填报窗口开始", null);
    }

    /**
     * 冻结合格学生名单和本批次导师范围。
     *
     * <p>调用方必须持有批次行锁并处于同一个事务。先以条件更新写冻结时刻并一次插入学生分母，再逐导师确定范围：
     * 完整配置沿用当前版本，不完整/未配置则按冻结时的目录生成默认全选版本，最后写入唯一范围槽位。
     * 这样名单、范围版本和冻结指针要么全部落库，要么全部回滚。</p>
     */
    private void freezeRosterAndScopes(SelectionBatchEntity batch, Long actorId) {
        int inserted = repository.freezeRosterSnapshot(batch.getId(), batch.getCollegeId(), batch.getAcademicYearId());
        if (inserted < 0) return;
        repository.insertAudit(null, "SYSTEM", null, "batchId=" + batch.getId(), "BATCH_STUDENT_ROSTER",
            batch.getId(), "BATCH_ROSTER_FROZEN", null,
            snapshot("studentCount", inserted, "frozenAt", "database UTC"), "填报窗口开始冻结资格名单", null);
        for (Long teacherId : repository.listBatchTeacherIds(batch.getId())) {
            if (repository.isScopeFrozen(batch.getId(), teacherId)) continue;
            TeacherScopeVersionEntity latest = repository.latestScope(batch.getId(), teacherId, true).orElse(null);
            List<Long> selectedIds = latest == null ? Collections.<Long>emptyList() :
                repository.listScopeMajorIds(latest.getId());
            boolean complete = latest != null && validDegreeMask(latest.getAllowedDegreeMask()) &&
                !selectedIds.isEmpty();
            Long scopeId;
            int versionNo;
            if (complete) {
                scopeId = latest.getId(); versionNo = latest.getVersionNo();
            } else {
                List<MajorVO> majors = repository.listActiveMajors(batch.getCollegeId());
                versionNo = latest == null ? 1 : latest.getVersionNo() + 1;
                scopeId = repository.insertScopeVersion(batch.getId(), teacherId, versionNo, 3,
                    batch.getCreatedBy(), "SYSTEM_DEFAULT", true);
                if (!majors.isEmpty()) repository.insertAllowedMajors(scopeId, majors);
            }
            repository.freezeScopeVersion(scopeId, batch.getId(), teacherId);
            repository.insertAudit(null, "SYSTEM", null, "batchId=" + batch.getId(),
                "TEACHER_APPLICATION_SCOPE", scopeId, "TEACHER_SCOPE_FROZEN", null,
                snapshot("teacherId", teacherId, "versionNo", versionNo,
                    "defaultAllApplied", !complete), "填报窗口开始冻结导师可报范围", null);
        }
    }

    /** 组装接口响应；未冻结配置为空时只计算默认值，不持久化默认版本。 */
    private TeacherApplicationScopeVO teacherScopeView(SelectionBatchEntity batch, Long teacherId) {
        Optional<TeacherScopeVersionEntity> latest = repository.latestScope(batch.getId(), teacherId, false);
        boolean frozen = repository.isScopeFrozen(batch.getId(), teacherId);
        List<String> types = new ArrayList<String>();
        List<Long> majorIds;
        boolean defaultAll;
        String source;
        int version;
        Instant configuredAt = null;
        Instant frozenAt = null;
        if (!latest.isPresent()) {
            types.add("ACADEMIC_MASTER"); types.add("PROFESSIONAL_MASTER");
            majorIds = repository.listActiveBatchMajorIds(batch.getId(), teacherId);
            if (majorIds.isEmpty()) majorIds = repository.listActiveMajors(batch.getCollegeId()).stream()
                .map(MajorVO::getId).collect(java.util.stream.Collectors.toList());
            defaultAll = true; source = "DEFAULT_PREVIEW"; version = 0;
        } else {
            TeacherScopeVersionEntity scope = latest.get();
            if ((scope.getAllowedDegreeMask() & 1) != 0) types.add("ACADEMIC_MASTER");
            if ((scope.getAllowedDegreeMask() & 2) != 0) types.add("PROFESSIONAL_MASTER");
            majorIds = repository.listScopeMajorIds(scope.getId());
            defaultAll = scope.isDefaultAllApplied(); source = scope.getScopeSource(); version = scope.getVersionNo();
            configuredAt = scope.getConfiguredAt() == null ? null : scope.getConfiguredAt().toInstant();
            frozenAt = scope.getFrozenAt() == null ? null : scope.getFrozenAt().toInstant();
            if (majorIds.isEmpty()) {
                majorIds = repository.listActiveMajors(batch.getCollegeId()).stream()
                    .map(MajorVO::getId).collect(java.util.stream.Collectors.toList());
                types.clear(); types.add("ACADEMIC_MASTER"); types.add("PROFESSIONAL_MASTER");
                defaultAll = true;
            }
        }
        return new TeacherApplicationScopeVO(batch.getId(), teacherId, version, types, majorIds,
            source, defaultAll, configuredAt, frozenAt, frozen);
    }

    /**
     * 规范化导师范围输入。
     *
     * <p>任一维度缺省或为空都视为配置不完整，按规则补为两种学位类型及学院全部启用专业；否则拒绝重复项、
     * 未知学位类型、停用专业和跨学院专业，返回同时包含快照行与响应 ID 的内部对象。</p>
     */
    private ScopeSelection normalizeScope(SetTeacherApplicationScopeRequest request, Long collegeId) {
        List<String> types = request.getAllowedDegreeTypes();
        List<Long> ids = request.getMajorIds();
        boolean incomplete = types == null || types.isEmpty() || ids == null || ids.isEmpty();
        int mask = 0;
        if (!incomplete) {
            Set<String> typeSet = new LinkedHashSet<String>(types);
            if (typeSet.size() != types.size() || typeSet.contains(null)) throw invalidArgument("学位类型不能重复或为空");
            for (String type : typeSet) {
                if ("ACADEMIC_MASTER".equals(type)) mask |= 1;
                else if ("PROFESSIONAL_MASTER".equals(type)) mask |= 2;
                else throw invalidArgument("学位类型只能选择学硕或专硕");
            }
            Set<Long> majorSet = new LinkedHashSet<Long>(ids);
            if (majorSet.size() != ids.size() || majorSet.contains(null) ||
                !repository.activeMajorsBelongToCollege(collegeId, new ArrayList<Long>(majorSet))) {
                throw invalidArgument("专业必须是本学院启用目录项且不能重复");
            }
        }
        if (incomplete) {
            List<MajorVO> majors = repository.listActiveMajors(collegeId);
            if (majors.isEmpty()) throw new ApiException("BATCH_CONFIGURATION_INCOMPLETE",
                "专业目录为空，无法设置默认范围", HttpStatus.CONFLICT);
            List<Long> allIds = new ArrayList<Long>();
            for (MajorVO major : majors) allIds.add(major.getId());
            return new ScopeSelection(3, majors, allIds,
                Arrays.asList("ACADEMIC_MASTER", "PROFESSIONAL_MASTER"), true);
        }
        List<Long> majorIds = new ArrayList<Long>(new LinkedHashSet<Long>(ids));
        List<MajorVO> majors = repository.findActiveMajorsByIds(collegeId, majorIds);
        return new ScopeSelection(mask, majors, majorIds, new ArrayList<String>(new LinkedHashSet<String>(types)), false);
    }

    /** 发布时对数据库中已保存排期做最后校验，不能只依赖前端或此前的排期请求校验。 */
    private void validateStoredSchedule(SelectionBatchEntity batch, List<BatchStageVO> stages) {
        Map<String, BatchStageVO> byCode = new LinkedHashMap<String, BatchStageVO>();
        for (BatchStageVO stage : stages) byCode.put(stage.getStageCode(), stage);
        Instant previousEnd = null;
        List<String> expected = new ArrayList<String>(CORE_STAGES);
        if (batch.isSupplementPlanned()) expected.add("SUPPLEMENT");
        for (String code : expected) {
            BatchStageVO stage = byCode.get(code);
            if (stage == null || stage.getPlannedStartAt() == null || stage.getPlannedEndAt() == null) {
                throw new ApiException("BATCH_CONFIGURATION_INCOMPLETE", "请补全全部必需阶段时间", HttpStatus.CONFLICT);
            }
            Instant start = stage.getPlannedStartAt(); Instant end = stage.getPlannedEndAt();
            if (!start.isBefore(end) || (previousEnd != null && start.isBefore(previousEnd))) {
                throw invalidArgument("阶段时间必须按顺序排列且不能相互重叠");
            }
            previousEnd = end;
        }
    }

    /** 将完整排期转换为 UTC 瞬时映射，并检查阶段代码唯一、必需阶段齐全且顺序不重叠。 */
    private Map<String, SchedulePoint> normalizeSchedule(BatchScheduleRequest request, boolean supplementPlanned) {
        Map<String, SchedulePoint> values = new LinkedHashMap<String, SchedulePoint>();
        for (BatchStageScheduleRequest stage : request.getStages()) {
            String code = trim(stage.getStageCode()).toUpperCase(java.util.Locale.ROOT);
            if (!CORE_STAGES.contains(code) && !"SUPPLEMENT".equals(code)) throw invalidArgument("阶段代码不受支持");
            if (values.containsKey(code)) throw invalidArgument("阶段排期重复");
            values.put(code, new SchedulePoint(parseUtc(stage.getPlannedStartAt()), parseUtc(stage.getPlannedEndAt())));
        }
        List<String> expected = new ArrayList<String>(CORE_STAGES);
        if (supplementPlanned) expected.add("SUPPLEMENT");
        if (values.size() != expected.size() || !values.keySet().containsAll(expected)) {
            throw invalidArgument(supplementPlanned ? "需提交填报、三轮及补选完整排期" : "需提交填报和三轮完整排期");
        }
        Instant previousEnd = null;
        for (String code : expected) {
            SchedulePoint point = values.get(code);
            if (!point.start.isBefore(point.end)) throw invalidArgument("阶段开始时间必须早于结束时间");
            if (previousEnd != null && point.start.isBefore(previousEnd)) throw invalidArgument("阶段不能相互重叠或倒序");
            previousEnd = point.end;
        }
        return values;
    }

    /**
     * 对已发布批次限制排期变更窗口。
     *
     * <p>整组请求中的历史/当前阶段必须保持原值，只有未开始阶段可调整；当前开放阶段保持原开始时间，
     * 且只能在原截止时刻前延长结束时间，避免借排期接口重开已过期窗口。</p>
     */
    private void validateScheduleChanges(List<BatchStageVO> oldStages, Map<String, SchedulePoint> schedule, Timestamp now) {
        Map<String, BatchStageVO> oldByCode = new LinkedHashMap<String, BatchStageVO>();
        for (BatchStageVO stage : oldStages) oldByCode.put(stage.getStageCode(), stage);
        Instant nowInstant = now.toInstant();
        for (Map.Entry<String, SchedulePoint> entry : schedule.entrySet()) {
            BatchStageVO old = oldByCode.get(entry.getKey());
            if (old == null) throw stateConflict("批次阶段不存在");
            if ("CLOSED".equals(old.getStatus())) {
                if (!old.getPlannedStartAt().equals(entry.getValue().start) ||
                    !old.getPlannedEndAt().equals(entry.getValue().end)) {
                    throw stateConflict("已关闭阶段不能修改排期");
                }
                continue;
            }
            if ("OPEN".equals(old.getStatus())) {
                if (old.getPlannedStartAt().equals(entry.getValue().start) &&
                    old.getPlannedEndAt().equals(entry.getValue().end)) continue;
                if (!old.getPlannedStartAt().equals(entry.getValue().start) ||
                    !entry.getValue().end.isAfter(old.getPlannedEndAt()) ||
                    !old.getPlannedEndAt().isAfter(nowInstant)) {
                    throw stateConflict("开放阶段只能在截止前延长结束时间");
                }
            }
        }
    }

    /** 由服务端重新读取摘要和阶段，确保写命令响应返回提交后的数据库值及新 rowVersion。 */
    private SelectionBatchDetailVO detail(Long batchId) {
        SelectionBatchEntity batch = requireBatch(batchId);
        List<SelectionBatchSummaryVO> summaries = repository.listBatches(batch.getCollegeId(),
            Collections.singletonList(batchId));
        if (summaries.isEmpty()) throw notFound("批次不存在");
        return new SelectionBatchDetailVO(summaries.get(0), repository.listStages(batchId));
    }

    /** 普通读取版本；资源不存在时返回统一 404。 */
    private SelectionBatchEntity requireBatch(Long batchId) {
        return repository.findBatch(batchId).orElseThrow(() -> notFound("批次不存在"));
    }

    /** 写事务版本；通过 SELECT FOR UPDATE 序列化同一批次的状态/配置命令。 */
    private SelectionBatchEntity lockBatch(Long batchId) {
        return repository.lockBatch(batchId).orElseThrow(() -> notFound("批次不存在"));
    }

    /** 按批次数据库归属校验 BATCH_MANAGER；无权批次按 404 隐藏，减少对象枚举信息。 */
    private AccountPrincipal requireBatchManager(AccountPrincipal actor, SelectionBatchEntity batch) {
        AccountPrincipal current = refresh(actor, ADMIN);
        if (!authorizationService.hasCapability(current, BATCH_MANAGER, batch.getCollegeId(), batch.getId())) {
            throw notFound("批次不存在或当前账号无权访问该批次");
        }
        return current;
    }

    /** BATCH_AUDIT 只开放批次查询、名额查看和统计；写命令继续调用 requireBatchManager。 */
    private AccountPrincipal requireBatchReader(AccountPrincipal actor, SelectionBatchEntity batch) {
        AccountPrincipal current = refresh(actor, ADMIN);
        if (isTotalAdmin(current) || authorizationService.hasCapability(current, BATCH_MANAGER,
            batch.getCollegeId(), batch.getId()) || authorizationService.hasCapability(current, BATCH_AUDIT,
            batch.getCollegeId(), batch.getId())) return current;
        throw notFound("批次不存在或当前账号无权访问该批次");
    }

    /** 非锁定地从数据库刷新账号状态、角色和授权，避免仅依赖登录时缓存的 Principal。 */
    private AccountPrincipal refresh(AccountPrincipal actor, String requiredRole) {
        if (actor == null || actor.getAccountId() == null) throw unauthenticated();
        AccountEntity entity = accountRepository.findById(actor.getAccountId())
            .orElseThrow(SelectionBatchManagementServiceImpl::unauthenticated);
        return principalFor(entity, actor, requiredRole);
    }

    /** 写命令先锁操作者账号行，再刷新角色和授权，串行化与账号停用/授权变化的竞态。 */
    private AccountPrincipal lockAndRefresh(AccountPrincipal actor, String requiredRole) {
        if (actor == null || actor.getAccountId() == null) throw unauthenticated();
        AccountEntity entity = accountRepository.findByIdForUpdate(actor.getAccountId())
            .orElseThrow(SelectionBatchManagementServiceImpl::unauthenticated);
        return principalFor(entity, actor, requiredRole);
    }

    /** 将最新账号投影重建为当前请求 Principal，并拒绝停用账号或错误角色。 */
    private AccountPrincipal principalFor(AccountEntity entity, AccountPrincipal actor, String requiredRole) {
        if (!"ACTIVE".equals(entity.getAccountStatus())) throw forbidden("账号已停用");
        if (!requiredRole.equals(entity.getRoleCode())) throw forbidden("当前账号角色不能执行此操作");
        try { return accountRepository.toPrincipal(entity, actor.isTemporaryCredentialLogin()); }
        catch (IllegalStateException ex) { throw unauthenticated(); }
    }

    /** 只允许对启用学院进行批次配置；不存在和已停用统一作为不可用资源处理。 */
    private void requireCollege(Long collegeId) {
        if (collegeId == null || !repository.findActiveCollege(collegeId).isPresent()) throw notFound("学院不存在或已停用");
    }

    /** 学院目录读取可由该学院任一管理/审计范围支持；创建操作仍要求学院级管理授权。 */
    private void requireAnyManagerScope(AccountPrincipal current, Long collegeId) {
        if (isTotalAdmin(current)) return;
        for (AccountAuthorization authorization : current.getAuthorizations()) {
            if ((BATCH_MANAGER.equals(authorization.getCapabilityCode()) ||
                BATCH_AUDIT.equals(authorization.getCapabilityCode())) &&
                collegeId.equals(authorization.getCollegeId())) return;
        }
        throw notFound("学院不存在或当前账号无权访问该学院批次范围");
    }

    /** 从数据库刷新的导师 Principal 读取身份主键；不接受请求体提供的导师 ID。 */
    private Long requireTeacherIdentity(AccountPrincipal current) {
        if (current.getIdentity() == null || current.getIdentity().getId() == null ||
            current.getIdentity().getCollegeId() == null) throw forbidden("当前账号没有有效导师身份");
        return current.getIdentity().getId();
    }

    /** 限制导师只能查询或修改其自身名额账户实际关联的批次。 */
    private void requireTeacherBatch(Long batchId, Long teacherId) {
        if (!repository.teacherHasBatchQuota(batchId, teacherId)) throw notFound("该批次不包含当前导师");
    }

    /** 查找命令幂等记录并校验请求指纹；重复键但请求内容不同会被拒绝。 */
    private Optional<OperationRecord> replay(Long actorId, String action, String key, String fingerprint) {
        Optional<OperationRecord> previous = repository.findOperation(actorId, action, key);
        if (previous.isPresent()) requireSameFingerprint(previous.get(), fingerprint);
        return previous;
    }

    private void requireSameFingerprint(OperationRecord operation, String fingerprint) {
        if (!fingerprint.equals(operation.getFingerprint())) {
            throw new ApiException("IDEMPOTENCY_KEY_REUSED", "该操作标识已用于其他请求", HttpStatus.CONFLICT);
        }
        if (!"OK".equals(operation.getResultCode())) {
            throw new ApiException("REQUEST_IN_PROGRESS", "该操作仍在处理中，请稍后查询", HttpStatus.CONFLICT);
        }
    }

    private void requireVersion(long current, long expected) {
        if (current != expected) throw preconditionFailed();
    }

    private String requireIdempotencyKey(String value) {
        if (value == null || !UUID_V4.matcher(value.trim()).matches()) throw invalidArgument("Idempotency-Key 必须是 UUID v4");
        return value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** 对规范化后的命令参数做 SHA-256 摘要，供幂等键重用时判定是否为同一请求。 */
    private String fingerprint(Object... values) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(Arrays.asList(values));
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format("%02x", b & 0xff));
            return out.toString();
        } catch (JsonProcessingException ex) { throw new IllegalStateException("Could not encode command fingerprint", ex); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 is unavailable", ex); }
    }

    private String snapshot(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < pairs.length; i += 2) map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        try { return objectMapper.writeValueAsString(map); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Could not encode audit snapshot", ex); }
    }

    private String scheduleSnapshot(List<BatchStageVO> stages) {
        List<Map<String, Object>> values = new ArrayList<Map<String, Object>>();
        for (BatchStageVO stage : stages) {
            Map<String, Object> value = new LinkedHashMap<String, Object>();
            value.put("stageCode", stage.getStageCode()); value.put("startAt", stage.getPlannedStartAt());
            value.put("endAt", stage.getPlannedEndAt()); values.add(value);
        }
        try { return objectMapper.writeValueAsString(values); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Could not encode schedule", ex); }
    }

    private String scheduleSnapshot(Map<String, SchedulePoint> stages) {
        List<Map<String, Object>> values = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, SchedulePoint> entry : stages.entrySet()) {
            Map<String, Object> value = new LinkedHashMap<String, Object>();
            value.put("stageCode", entry.getKey()); value.put("startAt", entry.getValue().start);
            value.put("endAt", entry.getValue().end); values.add(value);
        }
        try { return objectMapper.writeValueAsString(values); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Could not encode schedule", ex); }
    }

    /** 解析必须携带偏移量的 ISO-8601 时间，并立即转成与主机时区无关的 UTC 瞬时。 */
    private static Instant parseUtc(String value) {
        try { return OffsetDateTime.parse(value).toInstant(); }
        catch (DateTimeParseException ex) { throw invalidArgument("阶段时间必须采用包含时区的 ISO-8601 格式"); }
    }

    private static String trim(String value) { return value == null ? "" : value.trim(); }
    private static String optionalText(String value) { String result = trim(value); return result.isEmpty() ? null : result; }
    private static boolean validDegreeMask(int mask) { return mask >= 1 && mask <= 3; }

    private static boolean sameInstant(Instant left, Instant right) {
        return left != null && left.equals(right);
    }
    private static boolean isTotalAdmin(AccountPrincipal actor) {
        for (AccountAuthorization authorization : actor.getAuthorizations()) {
            if (ADMIN_ACCOUNT_MANAGER.equals(authorization.getCapabilityCode())) return true;
        }
        return false;
    }
    private static String scopeBasis(Long collegeId, Long batchId) {
        return "collegeId=" + collegeId + (batchId == null ? "" : "; batchId=" + batchId);
    }
    private static ApiException invalidArgument(String message) {
        return new ApiException("INVALID_ARGUMENT", message, HttpStatus.BAD_REQUEST);
    }
    private static ApiException forbidden(String message) {
        return new ApiException("SCOPE_FORBIDDEN", message, HttpStatus.FORBIDDEN);
    }
    private static ApiException notFound(String message) {
        return new ApiException("NOT_FOUND", message, HttpStatus.NOT_FOUND);
    }
    private static ApiException stateConflict(String message) {
        return new ApiException("STATE_CONFLICT", message, HttpStatus.CONFLICT);
    }
    private static ApiException preconditionFailed() {
        return new ApiException("PRECONDITION_FAILED", "数据已被其他请求修改，请刷新后重试", HttpStatus.PRECONDITION_FAILED);
    }
    private static ApiException unauthenticated() {
        return new ApiException("UNAUTHENTICATED", "登录状态已失效", HttpStatus.UNAUTHORIZED);
    }

    private static final class SchedulePoint {
        private final Instant start; private final Instant end;
        private SchedulePoint(Instant start, Instant end) { this.start = start; this.end = end; }
    }
    private static final class ScopeSelection {
        private final int degreeMask; private final List<MajorVO> majors; private final List<Long> majorIds;
        private final List<String> degreeTypes; private final boolean defaultAll;
        private ScopeSelection(int degreeMask, List<MajorVO> majors, List<Long> majorIds,
            List<String> degreeTypes, boolean defaultAll) {
            this.degreeMask = degreeMask; this.majors = majors; this.majorIds = majorIds;
            this.degreeTypes = degreeTypes; this.defaultAll = defaultAll;
        }
    }
}
