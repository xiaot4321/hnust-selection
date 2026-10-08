package cn.hnust.selection.service.impl;

import cn.hnust.selection.enums.AccountRole;
import cn.hnust.selection.exception.ApiException;
import cn.hnust.selection.repository.StudentSupplementMutationRepository;
import cn.hnust.selection.repository.StudentSupplementMutationRepository.Operation;
import cn.hnust.selection.repository.StudentSupplementMutationRepository.Participant;
import cn.hnust.selection.repository.StudentSupplementMutationRepository.EligibleTeacher;
import cn.hnust.selection.repository.StudentSupplementMutationRepository.SupplementWindow;
import cn.hnust.selection.request.SubmitSupplementApplicationRequest;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.StudentSupplementMutationService;
import cn.hnust.selection.vo.SupplementApplicationVO;
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
public class StudentSupplementMutationServiceImpl implements StudentSupplementMutationService {
    private static final String ACTION = "STUDENT_SUPPLEMENT_APPLY";
    private static final Pattern UUID_V4 = Pattern.compile(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");
    private final StudentSupplementMutationRepository repository;

    public StudentSupplementMutationServiceImpl(StudentSupplementMutationRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public SupplementApplicationVO submit(AccountPrincipal actor, Long batchId,
        SubmitSupplementApplicationRequest request, String idempotencyKey) {
        Long studentId = requireStudent(actor);
        if (batchId == null || batchId.longValue() <= 0 || request == null || request.getTeacherId() == null
            || request.getTeacherId().longValue() <= 0) {
            throw invalidArgument("batchId 和 teacherId 必须为正整数");
        }
        String key = normalizeKey(idempotencyKey);
        Long teacherId = request.getTeacherId();
        String fingerprint = fingerprint(batchId, teacherId);

        if (!repository.lockStudent(studentId, actor.getAccountId())) {
            throw new ApiException("NOT_FOUND", "学生身份不存在", HttpStatus.NOT_FOUND);
        }
        Optional<SupplementApplicationVO> replay = replay(actor.getAccountId(), key, fingerprint, true);
        if (replay.isPresent()) return replay.get();

        SupplementWindow window = repository.lockWindow(batchId)
            .orElseThrow(() -> supplementNotOpen());

        Timestamp now = repository.databaseUtcNow();
        requireOpenWindow(window, now);

        Participant participant = repository.lockParticipant(batchId, studentId, actor.getAccountId())
            .orElseThrow(() -> new ApiException("NOT_FOUND", "未找到本人在该批次的参与记录", HttpStatus.NOT_FOUND));
        requireEligibleParticipant(participant);
        if ("MATCHED".equals(participant.getMatchStatus())) {
            throw new ApiException("STUDENT_ALREADY_MATCHED", "学生已建立有效关系，不能申请补选", HttpStatus.CONFLICT);
        }
        if (!"UNMATCHED".equals(participant.getMatchStatus())) {
            throw new ApiException("STATE_CONFLICT", "当前学生状态不允许申请补选", HttpStatus.CONFLICT);
        }
        if (repository.hasActiveYearRelation(studentId, participant.getAcademicYearId())) {
            throw new ApiException("STUDENT_ALREADY_MATCHED", "学生本学年已建立有效关系", HttpStatus.CONFLICT);
        }
        if (repository.hasPendingApplication(studentId)) {
            throw new ApiException("SUPPLEMENT_PENDING_EXISTS", "已有待处理补选申请", HttpStatus.CONFLICT);
        }

        EligibleTeacher teacher = repository.findEligibleTeacher(batchId, window.getId(), teacherId,
                participant.getMajorId(), now)
            .orElseThrow(() -> new ApiException("SUPPLEMENT_CANDIDATE_NOT_AVAILABLE",
                "该导师当前不满足补选候选的年度资格、账号、资料或名额条件",
                HttpStatus.CONFLICT));
        int requiredDegreeBit = degreeBit(participant.getDegreeType());
        if ((teacher.getDegreeMask() & requiredDegreeBit) == 0 || !teacher.isMajorAllowed()) {
            throw new ApiException("TEACHER_SCOPE_MISMATCH", "学生专业或学位类型不在导师冻结招生范围内",
                HttpStatus.CONFLICT);
        }
        if (teacher.getOccupied() >= teacher.getQuotaLimit()) {
            throw new ApiException("QUOTA_EXHAUSTED", "导师当前没有可用名额", HttpStatus.CONFLICT);
        }

        int classificationVersion = participant.getClassificationVersion().intValue();
        Long operationId = repository.insertOperation(actor.getAccountId(), batchId, key, fingerprint,
            classificationVersion, teacher.getScopeVersionId());
        Long applicationId = repository.insertApplication(participant.getBatchStudentId(), window.getId(),
            teacher, participant.getStudentNo());
        Long snapshotId = repository.insertSnapshot(applicationId, participant);
        repository.setActiveSnapshot(applicationId, snapshotId);
        repository.claimPendingSlot(studentId, applicationId);
        repository.insertApplicationEvent(applicationId, actor.getAccountId(), operationId);
        repository.insertAudit(applicationId, operationId, actor.getAccountId(), batchId,
            participant.getBatchStudentId(), teacherId, classificationVersion);
        repository.completeOperation(operationId);
        return repository.findCommand(applicationId)
            .orElseThrow(() -> new IllegalStateException("New supplement application was not found"));
    }

    private Optional<SupplementApplicationVO> replay(Long accountId, String key, String fingerprint,
                                                      boolean currentRead) {
        Optional<Operation> found = currentRead
            ? repository.findOperationCurrent(accountId, key)
            : repository.findOperation(accountId, key);
        if (!found.isPresent()) return Optional.empty();
        Operation operation = found.get();
        if (!fingerprint.equals(operation.getFingerprint())) {
            throw new ApiException("IDEMPOTENCY_KEY_REUSED", "幂等键已用于不同请求", HttpStatus.CONFLICT);
        }
        if (!"OK".equals(operation.getResultCode()) || operation.getObjectId() == null) {
            throw new ApiException("REQUEST_IN_PROGRESS", "相同幂等请求仍在处理", HttpStatus.CONFLICT);
        }
        return Optional.of(repository.findCommand(operation.getObjectId())
            .orElseThrow(() -> new IllegalStateException("Idempotent supplement application was not found")));
    }

    private void requireOpenWindow(SupplementWindow window, Timestamp now) {
        if (!"ACTIVE".equals(window.getBatchStatus()) || !"OPEN".equals(window.getStatus())
            || window.getStartAt() == null || window.getEndAt() == null
            || now.before(window.getStartAt()) || !now.before(window.getEndAt())) {
            throw supplementNotOpen();
        }
    }

    private void requireEligibleParticipant(Participant participant) {
        if (!"ELIGIBLE".equals(participant.getEligibilitySnapshot()) || !participant.isAccountEnabled()) {
            throw new ApiException("FORBIDDEN", "当前批次名单不允许办理补选", HttpStatus.FORBIDDEN);
        }
        if (!"ACTIVE".equals(participant.getAccountStatus())) {
            throw new ApiException("ACCOUNT_DISABLED", "学生账号当前不可用", HttpStatus.FORBIDDEN);
        }
        if (participant.getClassificationVersion() == null || participant.getClassificationVersion().intValue() < 1) {
            throw new ApiException("STATE_CONFLICT", "学生身份分类尚未建立", HttpStatus.CONFLICT);
        }
    }

    private int degreeBit(String degreeType) {
        if ("ACADEMIC_MASTER".equals(degreeType)) return 1;
        if ("PROFESSIONAL_MASTER".equals(degreeType)) return 2;
        throw new ApiException("STATE_CONFLICT", "学生学位类型无效", HttpStatus.CONFLICT);
    }

    private String normalizeKey(String value) {
        if (value == null || !UUID_V4.matcher(value).matches()) {
            throw invalidArgument("Idempotency-Key 必须是 UUID v4");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private String fingerprint(Long batchId, Long teacherId) {
        return hash(ACTION + "|" + batchId + "|" + teacherId);
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

    private static Long requireStudent(AccountPrincipal actor) {
        if (actor == null || actor.getRole() != AccountRole.STUDENT || actor.getAccountId() == null
            || actor.getIdentity() == null || actor.getIdentity().getId() == null) {
            throw new ApiException("FORBIDDEN", "仅学生本人可申请补选", HttpStatus.FORBIDDEN);
        }
        return actor.getIdentity().getId();
    }

    private static ApiException supplementNotOpen() {
        return new ApiException("SUPPLEMENT_NOT_OPEN", "当前补选窗口未开放或已关闭", HttpStatus.CONFLICT);
    }

    private static ApiException invalidArgument(String message) {
        return new ApiException("INVALID_ARGUMENT", message, HttpStatus.BAD_REQUEST);
    }
}
