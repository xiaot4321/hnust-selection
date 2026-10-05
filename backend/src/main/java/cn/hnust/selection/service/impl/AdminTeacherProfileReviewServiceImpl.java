package cn.hnust.selection.service.impl;

import cn.hnust.selection.common.PageResult;
import cn.hnust.selection.exception.ApiException;
import cn.hnust.selection.repository.AdminTeacherProfileReviewRepository;
import cn.hnust.selection.repository.AdminTeacherProfileReviewRepository.Operation;
import cn.hnust.selection.repository.AdminTeacherProfileReviewRepository.ReviewTarget;
import cn.hnust.selection.request.ReviewTeacherProfileRequest;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.AdminTeacherProfileReviewService;
import cn.hnust.selection.service.PersonnelAccessService;
import cn.hnust.selection.vo.AdminTeacherProfileVersionVO;
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
public class AdminTeacherProfileReviewServiceImpl implements AdminTeacherProfileReviewService {
    private static final Pattern UUID_V4 = Pattern.compile("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    private final AdminTeacherProfileReviewRepository repository;
    private final PersonnelAccessService accessService;
    private final ObjectMapper objectMapper;

    public AdminTeacherProfileReviewServiceImpl(AdminTeacherProfileReviewRepository repository,
        PersonnelAccessService accessService, ObjectMapper objectMapper) {
        this.repository = repository; this.accessService = accessService; this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<AdminTeacherProfileVersionVO> listPending(AccountPrincipal actor, Long collegeId, int pageNo, int pageSize) {
        if (collegeId == null || collegeId.longValue() <= 0L) throw invalid("学院 ID 无效");
        validatePage(pageNo, pageSize);
        accessService.requireCollege(actor, collegeId);
        List<AdminTeacherProfileVersionVO> rows = repository.listPending(collegeId, pageNo, pageSize);
        return new PageResult<AdminTeacherProfileVersionVO>(rows, repository.countPending(collegeId), pageNo, pageSize);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminTeacherProfileVersionVO get(AccountPrincipal actor, Long versionId) {
        if (versionId == null || versionId.longValue() <= 0L) throw invalid("资料版本 ID 无效");
        AdminTeacherProfileVersionVO view = repository.findView(versionId).orElseThrow(() -> notFound("导师资料版本不存在"));
        accessService.requireCollege(actor, view.getCollegeId());
        return view;
    }

    @Override
    @Transactional
    public AdminTeacherProfileVersionVO review(AccountPrincipal actor, Long versionId, String ifMatch,
        ReviewTeacherProfileRequest request, String idempotencyKey) {
        if (versionId == null || versionId.longValue() <= 0L || request == null) throw invalid("审核请求不完整");
        String decision = request.getDecision() == null ? "" : request.getDecision().trim().toUpperCase(Locale.ROOT);
        if (!"APPROVE".equals(decision) && !"REJECT".equals(decision)) throw invalid("审核决定只能是 APPROVE 或 REJECT");
        String comment = request.getComment() == null ? "" : request.getComment().trim();
        if (comment.length() > 2000) throw invalid("审核意见不能超过 2000 个字符");
        if ("REJECT".equals(decision) && !StringUtils.hasText(comment)) throw invalid("驳回资料时必须填写意见");
        String key = normalizeKey(idempotencyKey);
        ReviewTarget target = repository.lockTarget(versionId).orElseThrow(() -> notFound("导师资料版本不存在"));
        AccountPrincipal current = accessService.requireCollege(actor, target.collegeId);
        String expectedEtag = AdminTeacherProfileReviewRepository.etag(versionId, target.rowVersion.longValue());
        if (ifMatch == null || ifMatch.trim().isEmpty()) {
            throw new ApiException("PRECONDITION_REQUIRED", "审核前请读取资料版本并发送 If-Match", HttpStatus.PRECONDITION_REQUIRED);
        }
        if (!expectedEtag.equals(stripQuotes(ifMatch.trim()))) {
            throw new ApiException("PRECONDITION_FAILED", "导师资料已变化，请重新读取后再审核", HttpStatus.PRECONDITION_FAILED);
        }
        String fingerprint = fingerprint(versionId, decision, comment, expectedEtag);
        Optional<Operation> replay = repository.findOperation(current.getAccountId(), key);
        if (replay.isPresent()) {
            Operation operation = replay.get();
            if (!fingerprint.equals(operation.fingerprint)) throw new ApiException("IDEMPOTENCY_KEY_REUSED", "幂等键已用于不同请求", HttpStatus.CONFLICT);
            if (!"OK".equals(operation.resultCode) || operation.objectId == null) {
                throw new ApiException("REQUEST_IN_PROGRESS", "相同审核仍在处理中", HttpStatus.CONFLICT);
            }
            return repository.findView(operation.objectId).orElseThrow(() -> new IllegalStateException("审核结果无法读取"));
        }
        if (!"PENDING_REVIEW".equals(target.status)) throw new ApiException("STATE_CONFLICT", "该资料版本已审核", HttpStatus.CONFLICT);
        Long operationId = repository.insertOperation(current.getAccountId(), target.collegeId, key, fingerprint);
        if (!repository.updateReview(target, decision, current.getAccountId(), comment)) {
            throw new ApiException("PRECONDITION_FAILED", "资料版本已变化，请重新读取", HttpStatus.PRECONDITION_FAILED);
        }
        String after = "{\"reviewStatus\":\"" + ("APPROVE".equals(decision) ? "APPROVED" : "REJECTED") + "\"}";
        repository.insertAudit(operationId, current.getAccountId(), target.collegeId, versionId,
            "{\"reviewStatus\":\"PENDING_REVIEW\",\"rowVersion\":" + target.rowVersion + "}", after, comment);
        repository.completeOperation(operationId);
        return repository.findView(versionId).orElseThrow(() -> new IllegalStateException("审核后资料版本无法读取"));
    }

    private String fingerprint(Long versionId, String decision, String comment, String etag) {
        try {
            byte[] data = objectMapper.writeValueAsBytes(new Object[] { versionId, decision, comment, etag });
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder result = new StringBuilder();
            for (byte value : digest) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return result.toString();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to fingerprint teacher profile review", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String normalizeKey(String key) {
        if (key == null || !UUID_V4.matcher(key.trim()).matches()) throw invalid("Idempotency-Key 必须是 UUID v4");
        return key.trim().toLowerCase(Locale.ROOT);
    }
    private static String stripQuotes(String value) {
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) return value.substring(1, value.length() - 1);
        return value;
    }
    private static void validatePage(int pageNo, int pageSize) {
        if (pageNo < 1 || pageSize < 1 || pageSize > 100) throw invalid("分页参数无效");
    }
    private static ApiException notFound(String message) { return new ApiException("NOT_FOUND", message, HttpStatus.NOT_FOUND); }
    private static ApiException invalid(String message) { return new ApiException("INVALID_ARGUMENT", message, HttpStatus.BAD_REQUEST); }
}
