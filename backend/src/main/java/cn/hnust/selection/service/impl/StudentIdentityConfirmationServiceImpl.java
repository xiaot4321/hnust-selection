package cn.hnust.selection.service.impl;

import cn.hnust.selection.enums.AccountRole;
import cn.hnust.selection.exception.ApiException;
import cn.hnust.selection.repository.StudentIdentityConfirmationRepository;
import cn.hnust.selection.repository.StudentIdentityConfirmationRepository.BatchStudentState;
import cn.hnust.selection.repository.StudentIdentityConfirmationRepository.Operation;
import cn.hnust.selection.repository.StudentIdentityConfirmationRepository.StageWindow;
import cn.hnust.selection.request.StudentIdentityConfirmationRequest;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.StudentIdentityConfirmationService;
import cn.hnust.selection.vo.StudentIdentityConfirmationVO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class StudentIdentityConfirmationServiceImpl implements StudentIdentityConfirmationService {
    private static final String ACTION = "STUDENT_IDENTITY_CONFIRM";
    private static final Pattern UUID_V4 = Pattern.compile(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");
    private final StudentIdentityConfirmationRepository repository;

    public StudentIdentityConfirmationServiceImpl(StudentIdentityConfirmationRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public StudentIdentityConfirmationVO confirm(AccountPrincipal actor, Long batchId,
        StudentIdentityConfirmationRequest request, String idempotencyKey) {
        Long studentId = requireStudent(actor);
        if (request == null || request.getClassificationVersion() == null || request.getClassificationVersion() < 1) {
            throw invalidArgument("classificationVersion 必须为正整数");
        }
        String key = normalizeKey(idempotencyKey);
        int requestedVersion = request.getClassificationVersion().intValue();
        String fingerprint = fingerprint(batchId, requestedVersion);

        Optional<StudentIdentityConfirmationVO> replay = replay(actor.getAccountId(), key, fingerprint);
        if (replay.isPresent()) return replay.get();
        StageWindow stage = repository.lockFillingStage(batchId)
            .orElseThrow(() -> new ApiException("NOT_FOUND", "未找到该批次的填报阶段", HttpStatus.NOT_FOUND));
        replay = replay(actor.getAccountId(), key, fingerprint);
        if (replay.isPresent()) return replay.get();

        Timestamp now = repository.databaseUtcNow();
        if (!"ACTIVE".equals(stage.getBatchStatus()) || !"OPEN".equals(stage.getStageStatus())
            || stage.getStartAt() == null || stage.getEndAt() == null
            || now.before(stage.getStartAt()) || !now.before(stage.getEndAt())) {
            throw new ApiException("BATCH_NOT_OPEN", "当前不在身份确认窗口", HttpStatus.CONFLICT);
        }
        BatchStudentState participant = repository.lockBatchStudent(batchId, studentId)
            .orElseThrow(() -> new ApiException("NOT_FOUND", "未找到本人在该批次的参与记录", HttpStatus.NOT_FOUND));
        if (!"ELIGIBLE".equals(participant.getEligibilitySnapshot()) || !participant.isAccountEnabled()) {
            throw new ApiException("FORBIDDEN", "当前批次名单不允许办理身份确认", HttpStatus.FORBIDDEN);
        }
        Integer currentVersion = repository.lockCurrentClassification(studentId, actor.getAccountId())
            .orElseThrow(() -> new ApiException("NOT_FOUND", "学生身份不存在或账号已停用", HttpStatus.NOT_FOUND));
        if (currentVersion.intValue() != requestedVersion) {
            throw new ApiException("STUDENT_CLASSIFICATION_CHANGED", "学生身份信息已更新，请重新核对", HttpStatus.CONFLICT);
        }

        Long operationId = repository.insertOperation(actor.getAccountId(), batchId, key, fingerprint, requestedVersion);
        repository.confirm(participant.getId(), requestedVersion);
        repository.insertAudit(operationId, actor.getAccountId(), batchId, participant.getId(), requestedVersion, key);
        repository.completeOperation(operationId);
        return repository.findConfirmation(participant.getId())
            .orElseThrow(() -> new IllegalStateException("Confirmed student identity was not found"));
    }

    private Optional<StudentIdentityConfirmationVO> replay(Long accountId, String key, String fingerprint) {
        Optional<Operation> existing = repository.findOperation(accountId, key);
        if (!existing.isPresent()) return Optional.empty();
        Operation operation = existing.get();
        if (!fingerprint.equals(operation.getFingerprint())) {
            throw new ApiException("IDEMPOTENCY_KEY_REUSED", "幂等键已用于不同请求", HttpStatus.CONFLICT);
        }
        if (!"OK".equals(operation.getResultCode()) || operation.getVersion() == null
            || operation.getBatchStudentId() == null || operation.getConfirmedAt() == null) {
            throw new ApiException("REQUEST_IN_PROGRESS", "相同幂等请求仍在处理", HttpStatus.CONFLICT);
        }
        return Optional.of(new StudentIdentityConfirmationVO(operation.getVersion(), operation.getConfirmedAt()));
    }

    private String normalizeKey(String value) {
        if (value == null || !UUID_V4.matcher(value).matches()) throw invalidArgument("Idempotency-Key 必须是 UUID v4");
        return value.toLowerCase(Locale.ROOT);
    }

    private String fingerprint(Long batchId, int version) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((ACTION + "|" + batchId + "|" + version).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format("%02x", value & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private static Long requireStudent(AccountPrincipal actor) {
        if (actor == null || actor.getRole() != AccountRole.STUDENT || actor.getAccountId() == null
            || actor.getIdentity() == null || actor.getIdentity().getId() == null) {
            throw new ApiException("FORBIDDEN", "仅学生本人可确认身份", HttpStatus.FORBIDDEN);
        }
        return actor.getIdentity().getId();
    }

    private static ApiException invalidArgument(String message) {
        return new ApiException("INVALID_ARGUMENT", message, HttpStatus.BAD_REQUEST);
    }
}
