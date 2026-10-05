package cn.hnust.selection.service.impl;

import cn.hnust.selection.common.PageResult;
import cn.hnust.selection.enums.AccountRole;
import cn.hnust.selection.exception.ApiException;
import cn.hnust.selection.repository.StudentIdentityCorrectionRepository;
import cn.hnust.selection.repository.StudentIdentityCorrectionRepository.IdentityBatchImpact;
import cn.hnust.selection.repository.StudentIdentityCorrectionRepository.IdentityCorrectionRecord;
import cn.hnust.selection.repository.StudentIdentityCorrectionRepository.Operation;
import cn.hnust.selection.request.CreateStudentIdentityCorrectionRequest;
import cn.hnust.selection.request.ReviewIdentityCorrectionRequest;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.AccountAuthorizationService;
import cn.hnust.selection.service.PersonnelAccessService;
import cn.hnust.selection.service.StudentIdentityCorrectionService;
import cn.hnust.selection.vo.AdminIdentityCorrectionVO;
import cn.hnust.selection.vo.StudentIdentityCorrectionCommandVO;
import cn.hnust.selection.vo.StudentIdentityCorrectionVO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class StudentIdentityCorrectionServiceImpl implements StudentIdentityCorrectionService {
    private static final String ACTION = "STUDENT_IDENTITY_CORRECTION_SUBMIT";
    private static final Pattern UUID_V4 = Pattern.compile(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");
    private static final String COLLEGE_ADMIN = "COLLEGE_ADMIN";
    private static final String ADMIN_DECISION_ACTION = "STUDENT_IDENTITY_CORRECTION_DECIDE";

    private final StudentIdentityCorrectionRepository repository;
    private final ObjectMapper objectMapper;
    private final PersonnelAccessService personnelAccessService;
    private final AccountAuthorizationService authorizationService;

    public StudentIdentityCorrectionServiceImpl(StudentIdentityCorrectionRepository repository, ObjectMapper objectMapper,
        PersonnelAccessService personnelAccessService, AccountAuthorizationService authorizationService) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.personnelAccessService = personnelAccessService;
        this.authorizationService = authorizationService;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<StudentIdentityCorrectionVO> listOwnRequests(AccountPrincipal actor, int pageNo, int pageSize) {
        Long studentId = requireStudent(actor);
        return new PageResult<StudentIdentityCorrectionVO>(repository.listOwnRequests(studentId, pageNo, pageSize),
            repository.countOwnRequests(studentId), pageNo, pageSize);
    }

    @Override
    @Transactional
    public StudentIdentityCorrectionCommandVO createRequest(AccountPrincipal actor,
        CreateStudentIdentityCorrectionRequest request, String idempotencyKey) {
        Long studentId = requireStudent(actor);
        if (request == null) throw invalidArgument("缺少身份更正申请内容");
        Long requestedMajorId = request.getRequestedMajorId();
        String requestedDegreeType = normalizeDegreeType(request.getRequestedDegreeType());
        String explanation = request.getStudentExplanation() == null ? "" : request.getStudentExplanation().trim();
        if (requestedMajorId == null && requestedDegreeType == null) {
            throw invalidArgument("至少提供一个需要核对的专业或学位类型");
        }
        if (!StringUtils.hasText(explanation)) throw invalidArgument("申请说明不能为空");
        String key = normalizeUuidV4(idempotencyKey);
        if (requestedMajorId != null && !repository.majorExists(requestedMajorId)) {
            throw new ApiException("NOT_FOUND", "申请的专业不存在", HttpStatus.NOT_FOUND);
        }

        // 学生行锁将当前分类读取、同账号幂等检查和申请创建串行化，避免同键并发创建两条记录。
        Integer classificationVersion = repository.lockStudentClassification(studentId, actor.getAccountId())
            .orElseThrow(() -> new ApiException("NOT_FOUND", "学生身份不存在", HttpStatus.NOT_FOUND));
        String fingerprint = fingerprint(requestedMajorId, requestedDegreeType, explanation);
        Optional<StudentIdentityCorrectionCommandVO> replay = replayIfPresent(actor.getAccountId(), key, fingerprint);
        if (replay.isPresent()) return replay.get();

        Long operationId = repository.insertOperation(actor.getAccountId(), ACTION, key,
            fingerprint, classificationVersion.intValue());
        Long requestId = repository.insertCorrectionRequest(studentId, classificationVersion.intValue(),
            requestedMajorId, requestedDegreeType, explanation);
        repository.insertAuditEvent(operationId, actor.getAccountId(), studentId, requestId,
            "{\"classificationVersion\":" + classificationVersion + "}",
            "{\"requestId\":" + requestId + ",\"status\":\"PENDING\"}");
        repository.completeOperation(operationId);
        return repository.findCommand(requestId)
            .orElseThrow(() -> new IllegalStateException("New student identity correction request was not found"));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<AdminIdentityCorrectionVO> listAdminRequests(AccountPrincipal actor, Long collegeId,
        String status, int pageNo, int pageSize) {
        if (collegeId == null || collegeId.longValue() <= 0L) throw invalidArgument("学院 ID 无效");
        validatePage(pageNo, pageSize);
        String requestStatus = status == null || status.trim().isEmpty() ? "PENDING" : status.trim().toUpperCase(Locale.ROOT);
        if (!"PENDING".equals(requestStatus) && !"APPROVED".equals(requestStatus) && !"REJECTED".equals(requestStatus)) {
            throw invalidArgument("申请状态无效");
        }
        personnelAccessService.requireCollege(actor, collegeId);
        return new PageResult<AdminIdentityCorrectionVO>(repository.listAdminRequests(collegeId, requestStatus, pageNo, pageSize),
            repository.countAdminRequests(collegeId, requestStatus), pageNo, pageSize);
    }

    @Override
    @Transactional
    public StudentIdentityCorrectionCommandVO decideAdminRequest(AccountPrincipal actor, Long requestId,
        ReviewIdentityCorrectionRequest request, String idempotencyKey) {
        if (requestId == null || requestId.longValue() <= 0L) throw invalidArgument("申请 ID 无效");
        if (request == null) throw invalidArgument("缺少审核决定");
        String decision = request.getDecision() == null ? "" : request.getDecision().trim().toUpperCase(Locale.ROOT);
        if (!"APPROVE".equals(decision) && !"REJECT".equals(decision)) throw invalidArgument("审核决定只能是 APPROVE 或 REJECT");
        String comment = request.getHandlingComment() == null ? "" : request.getHandlingComment().trim();
        if (comment.length() > 2000) throw invalidArgument("处理意见不能超过 2000 个字符");
        if ("REJECT".equals(decision) && !StringUtils.hasText(comment)) throw invalidArgument("驳回时必须填写处理意见");
        String key = normalizeUuidV4(idempotencyKey);

        IdentityCorrectionRecord correction = repository.lockAdminRequest(requestId)
            .orElseThrow(() -> notFound("身份更正申请不存在"));
        personnelAccessService.requireCollege(actor, correction.collegeId);
        String fingerprint = adminDecisionFingerprint(requestId, decision, comment);
        Optional<Operation> replay = repository.findAdminDecisionOperation(actor.getAccountId(), ADMIN_DECISION_ACTION, key);
        if (replay.isPresent()) {
            Operation operation = replay.get();
            if (!fingerprint.equals(operation.getFingerprint())) {
                throw new ApiException("IDEMPOTENCY_KEY_REUSED", "幂等键已用于不同请求", HttpStatus.CONFLICT);
            }
            if (!"OK".equals(operation.getResultCode()) || operation.getObjectId() == null) {
                throw new ApiException("REQUEST_IN_PROGRESS", "相同幂等请求仍在处理", HttpStatus.CONFLICT);
            }
            return repository.findCommand(operation.getObjectId())
                .orElseThrow(() -> new IllegalStateException("Idempotent identity correction decision result was not found"));
        }
        if (!"PENDING".equals(correction.status)) throw new ApiException("STATE_CONFLICT", "该申请已处理", HttpStatus.CONFLICT);

        Long operationId = repository.insertOperationForAdmin(actor.getAccountId(), correction.collegeId,
            ADMIN_DECISION_ACTION, key, fingerprint);
        Long revisionId = null;
        int impactNo = 0;
        if ("APPROVE".equals(decision)) {
            if (correction.classificationVersion != correction.requestedVersion) {
                throw new ApiException("STATE_CONFLICT", "学生身份已更新，请驳回旧申请并要求学生重新提交", HttpStatus.CONFLICT);
            }
            Long majorId = correction.requestedMajorId == null ? correction.currentMajorId : correction.requestedMajorId;
            String degreeType = correction.requestedDegreeType == null ? correction.currentDegreeType : normalizeDegreeType(correction.requestedDegreeType);
            if (!repository.activeMajorBelongsToCollege(majorId, correction.collegeId)) {
                throw new ApiException("INVALID_ARGUMENT", "申请专业已停用或不属于学生所在学院", HttpStatus.BAD_REQUEST);
            }
            List<IdentityBatchImpact> impacts = repository.lockBatchImpacts(correction.studentId);
            for (IdentityBatchImpact impact : impacts) {
                if (!impact.fillingStarted) continue;
                if (!("ACTIVE".equals(impact.batchStatus) || "PAUSED".equals(impact.batchStatus)) ||
                    !impact.supplementPlanned || impact.supplementWindowStatus == null ||
                    "CLOSED".equals(impact.supplementWindowStatus) || "NOT_SCHEDULED".equals(impact.supplementWindowStatus)) {
                    throw new ApiException("STATE_CONFLICT", "学生存在不在本期身份纠错办理范围内的已开窗批次（TODO-49）", HttpStatus.CONFLICT);
                }
            }
            if (!repository.updateStudentClassification(correction.studentId, correction.classificationVersion, majorId, degreeType)) {
                throw new ApiException("STATE_CONFLICT", "学生身份已变化，请重新核对", HttpStatus.CONFLICT);
            }
            revisionId = repository.insertClassificationRevision(correction, majorId, degreeType,
                comment.length() == 0 ? correction.explanation : comment, actor.getAccountId());
            for (IdentityBatchImpact impact : impacts) {
                if (impact.fillingStarted) {
                    repository.applyIdentityCorrectionToBatch(impact, correction.studentId, correction.requestId,
                        revisionId, ++impactNo, actor.getAccountId(), operationId);
                }
            }
        }
        String newStatus = "APPROVE".equals(decision) ? "APPROVED" : "REJECTED";
        repository.updateCorrectionRequest(correction.requestId, newStatus, actor.getAccountId(), comment, revisionId);
        repository.insertAdminAudit(operationId, actor.getAccountId(), correction.collegeId, correction.requestId,
            ADMIN_DECISION_ACTION, "{\"status\":\"PENDING\",\"classificationVersion\":" + correction.classificationVersion + "}",
            "{\"status\":\"" + newStatus + "\",\"resultingRevisionId\":" + (revisionId == null ? "null" : revisionId) + "}",
            comment, comment);
        repository.completeAdminOperation(operationId);
        return repository.findCommand(correction.requestId)
            .orElseThrow(() -> new IllegalStateException("Identity correction decision result was not found"));
    }

    private String adminDecisionFingerprint(Long requestId, String decision, String comment) {
        try {
            byte[] normalized = objectMapper.writeValueAsBytes(new AdminDecisionFingerprint(requestId, decision, comment));
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return result.toString();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to fingerprint identity correction decision", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void validatePage(int pageNo, int pageSize) {
        if (pageNo < 1 || pageSize < 1 || pageSize > 100) throw invalidArgument("分页参数无效");
    }

    private static ApiException notFound(String message) {
        return new ApiException("NOT_FOUND", message, HttpStatus.NOT_FOUND);
    }

    private static final class AdminDecisionFingerprint {
        public final Long requestId; public final String decision; public final String comment;
        AdminDecisionFingerprint(Long requestId, String decision, String comment) {
            this.requestId=requestId; this.decision=decision; this.comment=comment;
        }
    }

    private Optional<StudentIdentityCorrectionCommandVO> replayIfPresent(Long accountId, String key, String fingerprint) {
        Optional<Operation> existing = repository.findOperation(accountId, ACTION, key);
        if (!existing.isPresent()) return Optional.empty();
        Operation operation = existing.get();
        if (!fingerprint.equals(operation.getFingerprint())) {
            throw new ApiException("IDEMPOTENCY_KEY_REUSED", "幂等键已用于不同请求", HttpStatus.CONFLICT);
        }
        if (!"OK".equals(operation.getResultCode()) || operation.getObjectId() == null) {
            throw new ApiException("REQUEST_IN_PROGRESS", "相同幂等请求仍在处理", HttpStatus.CONFLICT);
        }
        return Optional.of(repository.findCommand(operation.getObjectId())
            .orElseThrow(() -> new IllegalStateException("Idempotent student request result was not found")));
    }

    private String fingerprint(Long requestedMajorId, String requestedDegreeType, String explanation) {
        try {
            byte[] normalized = objectMapper.writeValueAsBytes(new Object[] {
                ACTION, requestedMajorId, requestedDegreeType, explanation
            });
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format("%02x", value & 0xff));
            return result.toString();
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Unable to fingerprint a student identity correction request", ex);
        }
    }

    private String normalizeUuidV4(String value) {
        if (value == null || !UUID_V4.matcher(value).matches()) {
            throw invalidArgument("Idempotency-Key 必须是 UUID v4");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private String normalizeDegreeType(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.isEmpty()) return null;
        if (!"ACADEMIC_MASTER".equals(normalized) && !"PROFESSIONAL_MASTER".equals(normalized)) {
            throw invalidArgument("requestedDegreeType 取值无效");
        }
        return normalized;
    }

    private static Long requireStudent(AccountPrincipal actor) {
        if (actor == null || actor.getRole() != AccountRole.STUDENT || actor.getIdentity() == null
            || actor.getIdentity().getId() == null || actor.getAccountId() == null) {
            throw new ApiException("FORBIDDEN", "仅学生本人可操作身份更正申请", HttpStatus.FORBIDDEN);
        }
        return actor.getIdentity().getId();
    }

    private static ApiException invalidArgument(String message) {
        return new ApiException("INVALID_ARGUMENT", message, HttpStatus.BAD_REQUEST);
    }
}
