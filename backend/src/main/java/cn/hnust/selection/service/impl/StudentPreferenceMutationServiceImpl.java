package cn.hnust.selection.service.impl;

import cn.hnust.selection.enums.AccountRole;
import cn.hnust.selection.exception.ApiException;
import cn.hnust.selection.repository.StudentPreferenceMutationRepository;
import cn.hnust.selection.repository.StudentPreferenceMutationRepository.BatchStudentState;
import cn.hnust.selection.repository.StudentPreferenceMutationRepository.Operation;
import cn.hnust.selection.repository.StudentPreferenceMutationRepository.StageWindow;
import cn.hnust.selection.repository.StudentPreferenceMutationRepository.StudentClassification;
import cn.hnust.selection.repository.StudentPreferenceMutationRepository.TeacherScope;
import cn.hnust.selection.request.SubmitStudentPreferencesRequest;
import cn.hnust.selection.request.SubmitStudentPreferencesRequest.PreferenceItemRequest;
import cn.hnust.selection.request.WithdrawStudentPreferencesRequest;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.StudentPreferenceMutationService;
import cn.hnust.selection.vo.StudentPreferenceCommandVO;
import cn.hnust.selection.vo.StudentPreferenceWithdrawalVO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class StudentPreferenceMutationServiceImpl implements StudentPreferenceMutationService {
    private static final String SUBMIT_ACTION = "STUDENT_PREFERENCE_SUBMIT";
    private static final String WITHDRAW_ACTION = "STUDENT_PREFERENCE_WITHDRAW";
    private static final Pattern UUID_V4 = Pattern.compile(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");
    private final StudentPreferenceMutationRepository repository;

    public StudentPreferenceMutationServiceImpl(StudentPreferenceMutationRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public StudentPreferenceCommandVO submit(AccountPrincipal actor, Long batchId,
        SubmitStudentPreferencesRequest request, String idempotencyKey) {
        Long studentId = requireStudent(actor);
        validatePreferenceItems(request);
        String key = normalizeKey(idempotencyKey);
        int requestedClassificationVersion = request.getIdentityClassificationVersion().intValue();
        List<PreferenceItemRequest> items = request.getItems();
        String fingerprint = submitFingerprint(batchId, requestedClassificationVersion, items);

        Optional<StudentPreferenceCommandVO> replay = replaySubmission(actor.getAccountId(), key, fingerprint);
        if (replay.isPresent()) return replay.get();
        StageWindow stage = repository.lockFillingStage(batchId)
            .orElseThrow(() -> new ApiException("NOT_FOUND", "未找到该批次的填报阶段", HttpStatus.NOT_FOUND));
        replay = replaySubmission(actor.getAccountId(), key, fingerprint);
        if (replay.isPresent()) return replay.get();
        requireFillingWindow(stage, repository.databaseUtcNow());

        BatchStudentState participant = repository.lockBatchStudent(batchId, studentId)
            .orElseThrow(() -> new ApiException("NOT_FOUND", "未找到本人在该批次的参与记录", HttpStatus.NOT_FOUND));
        requireEligibleParticipant(participant);
        if ("MATCHED".equals(participant.getMatchStatus()) || participant.getFinalSubmissionId() != null
            || "LOCKED".equals(participant.getPreferenceStatus())) {
            throw stateConflict("志愿已锁定或学生已匹配，不能再提交新版本");
        }

        StudentClassification classification = repository.lockStudentClassification(studentId, actor.getAccountId())
            .orElseThrow(() -> new ApiException("NOT_FOUND", "学生身份不存在或账号已停用", HttpStatus.NOT_FOUND));
        if (classification.getVersion().intValue() != requestedClassificationVersion) {
            throw new ApiException("STUDENT_CLASSIFICATION_CHANGED", "学生身份信息已更新，请重新核对", HttpStatus.CONFLICT);
        }
        if (participant.getConfirmedVersion() == null
            || participant.getConfirmedVersion().intValue() != classification.getVersion().intValue()) {
            throw new ApiException("IDENTITY_CONFIRMATION_REQUIRED", "请先确认当前专业和学位类型", HttpStatus.CONFLICT);
        }

        Set<Long> seenTeachers = new HashSet<Long>();
        for (int index = 0; index < items.size(); index++) {
            PreferenceItemRequest item = items.get(index);
            if (item.getPreferenceOrder() == null || item.getPreferenceOrder().intValue() != index + 1) {
                throw invalidArgument("preferenceOrder 必须按请求数组顺序连续为 1…N");
            }
            Long teacherId = item.getTeacherId();
            if (!seenTeachers.add(teacherId)) {
                throw new ApiException("PREFERENCE_DUPLICATE_TEACHER", "同一导师不能重复填入多个顺位", HttpStatus.BAD_REQUEST);
            }
        }

        int degreeMask = degreeMask(classification.getDegreeType());
        java.util.Map<Long, TeacherScope> scopesByTeacher = new java.util.LinkedHashMap<Long, TeacherScope>();
        for (PreferenceItemRequest item : items) {
            TeacherScope scope = repository.findFrozenTeacherScope(batchId, item.getTeacherId(),
                classification.getMajorId(), classification.getDegreeType())
                .orElseThrow(() -> new ApiException("TEACHER_SCOPE_MISMATCH", "所选导师当前不在本批次可报范围内", HttpStatus.CONFLICT));
            if ((scope.getAllowedDegreeMask() & degreeMask) == 0 || !scope.isMajorAllowed()) {
                throw new ApiException("TEACHER_SCOPE_MISMATCH", "所选导师的冻结招生范围不符合当前身份", HttpStatus.CONFLICT);
            }
            scopesByTeacher.put(item.getTeacherId(), scope);
        }

        int versionNo = repository.nextVersion(participant.getId());
        Long operationId = repository.insertOperation(actor.getAccountId(), batchId, SUBMIT_ACTION, key,
            fingerprint, requestedClassificationVersion);
        Long submissionId = repository.insertSubmission(participant.getId(), versionNo, items.size(), actor.getAccountId());
        for (int index = 0; index < items.size(); index++) {
            PreferenceItemRequest item = items.get(index);
            TeacherScope scope = scopesByTeacher.get(item.getTeacherId());
            repository.insertItem(submissionId, index + 1, scope, requestedClassificationVersion,
                classification.getMajorId(), classification.getDegreeType());
        }
        repository.setCurrentSubmission(participant.getId(), submissionId);
        repository.insertApplicationEvent(submissionId, "PREFERENCE_SUBMITTED", null, "SUBMITTED", null,
            actor.getAccountId(), operationId);
        repository.insertAuditEvent(operationId, actor.getAccountId(), batchId, participant.getId(),
            SUBMIT_ACTION, submissionId,
            "{\"preferenceStatus\":\"" + participant.getPreferenceStatus() + "\"}",
            "{\"submissionId\":" + submissionId + ",\"versionNo\":" + versionNo + ",\"itemCount\":" + items.size() + "}",
            null);
        repository.completeOperation(operationId);
        return repository.findSubmissionCommand(submissionId)
            .orElseThrow(() -> new IllegalStateException("New preference submission was not found"));
    }

    @Override
    @Transactional
    public StudentPreferenceWithdrawalVO withdraw(AccountPrincipal actor, Long batchId,
        WithdrawStudentPreferencesRequest request, String idempotencyKey) {
        Long studentId = requireStudent(actor);
        String reason = request == null || request.getReason() == null ? null : request.getReason().trim();
        if (reason != null && reason.isEmpty()) reason = null;
        String key = normalizeKey(idempotencyKey);
        String fingerprint = hash(WITHDRAW_ACTION + "|" + batchId + "|" + (reason == null ? "" : reason));

        Optional<StudentPreferenceWithdrawalVO> replay = replayWithdrawal(actor.getAccountId(), key, fingerprint);
        if (replay.isPresent()) return replay.get();
        StageWindow stage = repository.lockFillingStage(batchId)
            .orElseThrow(() -> new ApiException("NOT_FOUND", "未找到该批次的填报阶段", HttpStatus.NOT_FOUND));
        replay = replayWithdrawal(actor.getAccountId(), key, fingerprint);
        if (replay.isPresent()) return replay.get();
        requireFillingWindow(stage, repository.databaseUtcNow());

        BatchStudentState participant = repository.lockBatchStudent(batchId, studentId)
            .orElseThrow(() -> new ApiException("NOT_FOUND", "未找到本人在该批次的参与记录", HttpStatus.NOT_FOUND));
        requireEligibleParticipant(participant);
        if ("MATCHED".equals(participant.getMatchStatus()) || !"SUBMITTED".equals(participant.getPreferenceStatus())) {
            throw stateConflict("当前没有可撤回的有效志愿");
        }
        Long submissionId = repository.lockCurrentSubmission(participant.getId())
            .orElseThrow(() -> stateConflict("当前志愿已锁定或状态已变化"));
        Long operationId = repository.insertOperation(actor.getAccountId(), batchId, WITHDRAW_ACTION,
            key, fingerprint, null);
        repository.withdrawSubmission(submissionId);
        repository.clearCurrentSubmission(participant.getId());
        repository.insertApplicationEvent(submissionId, "PREFERENCE_WITHDRAWN", "SUBMITTED", "WITHDRAWN",
            reason, actor.getAccountId(), operationId);
        repository.insertAuditEvent(operationId, actor.getAccountId(), batchId, participant.getId(),
            WITHDRAW_ACTION, submissionId, "{\"preferenceStatus\":\"SUBMITTED\"}",
            "{\"preferenceStatus\":\"WITHDRAWN\"}", reason);
        repository.completeOperation(operationId);
        return new StudentPreferenceWithdrawalVO("WITHDRAWN");
    }

    private Optional<StudentPreferenceCommandVO> replaySubmission(Long accountId, String key, String fingerprint) {
        Optional<Operation> operation = repository.findOperation(accountId, SUBMIT_ACTION, key);
        if (!operation.isPresent()) return Optional.empty();
        Operation existing = operation.get();
        validateReplay(existing, fingerprint);
        return Optional.of(repository.findSubmissionCommand(existing.getObjectId())
            .orElseThrow(() -> new IllegalStateException("Idempotent preference submission was not found")));
    }

    private Optional<StudentPreferenceWithdrawalVO> replayWithdrawal(Long accountId, String key, String fingerprint) {
        Optional<Operation> operation = repository.findOperation(accountId, WITHDRAW_ACTION, key);
        if (!operation.isPresent()) return Optional.empty();
        validateReplay(operation.get(), fingerprint);
        return Optional.of(new StudentPreferenceWithdrawalVO("WITHDRAWN"));
    }

    private void validateReplay(Operation operation, String fingerprint) {
        if (!fingerprint.equals(operation.getFingerprint())) {
            throw new ApiException("IDEMPOTENCY_KEY_REUSED", "幂等键已用于不同请求", HttpStatus.CONFLICT);
        }
        if (!"OK".equals(operation.getResultCode()) || operation.getObjectId() == null) {
            throw new ApiException("REQUEST_IN_PROGRESS", "相同幂等请求仍在处理", HttpStatus.CONFLICT);
        }
    }

    private String submitFingerprint(Long batchId, int classificationVersion, List<PreferenceItemRequest> items) {
        StringBuilder value = new StringBuilder(SUBMIT_ACTION).append('|').append(batchId).append('|').append(classificationVersion);
        for (PreferenceItemRequest item : items) value.append('|').append(item.getPreferenceOrder()).append(':').append(item.getTeacherId());
        return hash(value.toString());
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format("%02x", item & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private String normalizeKey(String value) {
        if (value == null || !UUID_V4.matcher(value).matches()) throw invalidArgument("Idempotency-Key 必须是 UUID v4");
        return value.toLowerCase(Locale.ROOT);
    }

    private void validatePreferenceItems(SubmitStudentPreferencesRequest request) {
        if (request == null || request.getIdentityClassificationVersion() == null
            || request.getIdentityClassificationVersion().intValue() <= 0) {
            throw invalidArgument("identityClassificationVersion 必须为正整数");
        }
        if (request.getItems() == null || request.getItems().isEmpty() || request.getItems().size() > 3) {
            throw new ApiException("PREFERENCE_COUNT_INVALID", "一次提交须包含 1 至 3 项志愿", HttpStatus.BAD_REQUEST);
        }
        for (PreferenceItemRequest item : request.getItems()) {
            if (item == null || item.getTeacherId() == null || item.getTeacherId().longValue() <= 0
                || item.getPreferenceOrder() == null || item.getPreferenceOrder().intValue() <= 0) {
                throw invalidArgument("每项志愿均须提供有效导师和顺位");
            }
        }
    }

    private void requireFillingWindow(StageWindow stage, Timestamp now) {
        if (!"ACTIVE".equals(stage.getBatchStatus()) || !"OPEN".equals(stage.getStageStatus())
            || stage.getStartAt() == null || stage.getEndAt() == null
            || now.before(stage.getStartAt()) || !now.before(stage.getEndAt())) {
            throw new ApiException("PREFERENCE_WINDOW_CLOSED", "当前不在志愿填报窗口", HttpStatus.CONFLICT);
        }
    }

    private void requireEligibleParticipant(BatchStudentState participant) {
        if (!"ELIGIBLE".equals(participant.getEligibilitySnapshot()) || !participant.isAccountEnabled()) {
            throw new ApiException("FORBIDDEN", "当前批次名单不允许办理志愿业务", HttpStatus.FORBIDDEN);
        }
    }

    private int degreeMask(String degreeType) {
        if ("ACADEMIC_MASTER".equals(degreeType)) return 1;
        if ("PROFESSIONAL_MASTER".equals(degreeType)) return 2;
        throw invalidArgument("学生学位类型无效");
    }

    private static Long requireStudent(AccountPrincipal actor) {
        if (actor == null || actor.getRole() != AccountRole.STUDENT || actor.getAccountId() == null
            || actor.getIdentity() == null || actor.getIdentity().getId() == null) {
            throw new ApiException("FORBIDDEN", "仅学生本人可提交或撤回本人志愿", HttpStatus.FORBIDDEN);
        }
        return actor.getIdentity().getId();
    }

    private static ApiException invalidArgument(String message) {
        return new ApiException("INVALID_ARGUMENT", message, HttpStatus.BAD_REQUEST);
    }

    private static ApiException stateConflict(String message) {
        return new ApiException("STATE_CONFLICT", message, HttpStatus.CONFLICT);
    }
}

