package cn.hnust.selection.service.impl;

import cn.hnust.selection.entity.PersonnelImportEntity;
import cn.hnust.selection.entity.PersonnelImportRowEntity;
import cn.hnust.selection.entity.PersonnelOperationEntity;
import cn.hnust.selection.exception.ApiException;
import cn.hnust.selection.repository.PersonnelManagementRepository;
import cn.hnust.selection.request.CreateStudentRequest;
import cn.hnust.selection.request.CreateTeacherRequest;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.PersonnelAccessService;
import cn.hnust.selection.service.PersonnelImportService;
import cn.hnust.selection.service.PersonnelRegistrationService;
import cn.hnust.selection.service.PersonnelRegistrationService.CredentialResult;
import cn.hnust.selection.service.PersonnelRegistrationService.EligibilityInput;
import cn.hnust.selection.service.PersonnelRegistrationService.RegisteredPerson;
import cn.hnust.selection.service.PrivateFileStorage;
import cn.hnust.selection.service.PrivateFileStorage.StoredPrivateFile;
import cn.hnust.selection.utils.PersonnelImportFileParser;
import cn.hnust.selection.vo.PersonnelImportRowVO;
import cn.hnust.selection.vo.PersonnelImportVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 名单导入应用服务实现。
 *
 * <p>本类负责导入任务级校验、文件私有存储、逐行注册、幂等重放和结果审计。每一行的账号、档案、
 * 年度资格和临时凭证通过 {@link PersonnelRegistrationService} 在独立事务中创建；本类不直接
 * 执行 SQL，也不会把凭证明文写入导入历史。</p>
 */
@Service
public class PersonnelImportServiceImpl implements PersonnelImportService {
    private static final String TEMPLATE_VERSION = "1.0";
    private static final int MAX_IMPORT_ROWS = 2000;
    private static final Pattern UUID_V4 = Pattern.compile(
        "(?i)^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    private static final List<String> STUDENT_HEADERS = Arrays.asList("loginIdentifier", "studentNo", "fullName",
        "majorCode", "degreeType", "enrollmentYearCode", "eligibilityStatus", "evidenceType", "evidenceReference");
    private static final List<String> TEACHER_HEADERS = Arrays.asList("loginIdentifier", "employeeNo", "fullName",
        "eligibilityStatus", "evidenceType", "evidenceReference");

    private final PersonnelManagementRepository repository;
    private final PersonnelAccessService accessService;
    private final PersonnelRegistrationService registrationService;
    private final PrivateFileStorage privateFileStorage;
    private final ObjectMapper objectMapper;

    public PersonnelImportServiceImpl(PersonnelManagementRepository repository,
                                      PersonnelAccessService accessService,
                                      PersonnelRegistrationService registrationService,
                                      PrivateFileStorage privateFileStorage,
                                      ObjectMapper objectMapper) {
        this.repository = repository;
        this.accessService = accessService;
        this.registrationService = registrationService;
        this.privateFileStorage = privateFileStorage;
        this.objectMapper = objectMapper;
    }

    /** 下载固定模板前先校验学院范围，模板字段顺序与导入解析器保持一致。 */
    @Override
    @Transactional(readOnly = true)
    public String csvTemplate(AccountPrincipal actor, Long collegeId, String personType) {
        accessService.requireCollege(actor, collegeId);
        return String.join(",", headers(personType)) + "\r\n";
    }

    /**
     * 保存私有源文件和导入任务，再按文件原始行序逐条调用单行事务服务。
     *
     * <p>每次导入最多处理 2000 条数据行。学院、学年和人员类型由请求参数及服务端授权控制，
     * 文件本身不能扩大导入范围。首次响应可以返回新账号临时凭证，历史响应会隐藏凭证明文。</p>
     */
    @Override
    public PersonnelImportVO importPersonnel(AccountPrincipal actor, Long collegeId, Long academicYearId,
                                             String personType, MultipartFile file, String idempotencyKey) {
        AccountPrincipal current = accessService.requireCollege(actor, collegeId);
        if (!repository.academicYearExists(academicYearId)) {
            throw notFound("学年不存在");
        }

        List<String> expectedHeaders = headers(personType);
        byte[] content;
        List<List<String>> rows;
        try {
            content = file.getBytes();
            rows = PersonnelImportFileParser.parse(content, file.getOriginalFilename());
        } catch (IOException exception) {
            throw invalid(exception.getMessage() == null ? "名单文件无法读取" : exception.getMessage());
        }
        if (rows.size() < 2) {
            throw invalid("名单文件必须包含表头和至少一条人员记录");
        }
        if (rows.size() - 1 > MAX_IMPORT_ROWS) {
            throw invalid("单个名单最多包含 " + MAX_IMPORT_ROWS + " 条人员记录");
        }
        validateHeaders(rows.get(0), expectedHeaders);

        String key = requireIdempotencyKey(idempotencyKey);
        String action = "PERSONNEL_IMPORT";
        String fingerprint = hash(action + "\u0000" + collegeId + "\u0000" + academicYearId + "\u0000" +
            personType + "\u0000" + sha256(content));
        Optional<PersonnelOperationEntity> replay = replay(current.getAccountId(), action, key, fingerprint);
        if (replay.isPresent()) {
            return replayImport(current, replay.get());
        }

        Long operationId = repository.insertOperation(current.getAccountId(), action, collegeId, key, fingerprint);
        StoredPrivateFile stored = null;
        try {
            stored = privateFileStorage.store(file);
            repository.setManagedFile(current.getAccountId(), stored.getFilename(), stored.getMediaType(),
                stored.getSize(), stored.getDigest(), stored.getKey());
            Long fileId = repository.managedFileId(stored.getKey());
            Long importId = repository.insertImport(current.getAccountId(), collegeId, academicYearId,
                personType, fileId, operationId);

            List<PersonnelImportRowVO> results = new ArrayList<PersonnelImportRowVO>();
            int accepted = 0;
            int rejected = 0;
            Set<String> fileKeys = new HashSet<String>();
            for (int sourceIndex = 1; sourceIndex < rows.size(); sourceIndex++) {
                int rowNumber = sourceIndex + 1; // 表头占第 1 行，返回行号与电子表格行号保持一致。
                List<String> cells = rows.get(sourceIndex);
                PersonnelImportRowVO rowResult;
                try {
                    RegisteredPerson registered = importRow(current, operationId, collegeId, academicYearId,
                        personType, stored.getFilename(), expectedHeaders.size(), cells, fileKeys);
                    repository.insertImportRow(importId, rowNumber, safeCell(cells, 1), "CREATED", null, null,
                        registered.getPersonId(), registered.getEligibilityId(), "TEACHER".equals(personType));
                    rowResult = createdRow(rowNumber, cells, registered);
                    accepted++;
                } catch (RowImportException exception) {
                    rowResult = rejectedRow(importId, rowNumber, cells, personType,
                        exception.getCode(), exception.getMessage());
                    rejected++;
                } catch (RuntimeException exception) {
                    rowResult = rejectedRow(importId, rowNumber, cells, personType,
                        errorCode(exception), safeError(exception));
                    rejected++;
                }
                results.add(rowResult);
            }

            String status = rejected == 0 ? "COMPLETED"
                : accepted == 0 ? "FAILED" : "COMPLETED_WITH_ERRORS";
            repository.finishImport(importId, accepted, rejected, status);
            repository.insertAudit(operationId, current.getAccountId(), collegeId, "PERSONNEL_IMPORT", importId,
                action, json("personType", personType, "acceptedCount", accepted, "rejectedCount", rejected,
                    "templateVersion", TEMPLATE_VERSION), "按固定模板逐行处理名单；源文件保存在私有目录");
            repository.completeOperation(operationId);
            return new PersonnelImportVO(importId, personType, status, accepted, rejected, results);
        } catch (IOException exception) {
            repository.failOperation(operationId);
            privateFileStorage.delete(stored);
            throw invalid(exception.getMessage() == null ? "无法将源文件写入私有存储目录" : exception.getMessage());
        } catch (RuntimeException exception) {
            repository.failOperation(operationId);
            privateFileStorage.delete(stored);
            throw exception;
        }
    }

    /** 读取幂等命令对应的完整结果；历史记录只包含脱敏后的逐行数据。 */
    private PersonnelImportVO replayImport(AccountPrincipal actor, PersonnelOperationEntity operation) {
        Long importId = repository.importIdForOperation(operation.getId())
            .orElseThrow(() -> conflict("幂等导入记录不存在"));
        PersonnelImportEntity existing = repository.findImport(importId)
            .orElseThrow(() -> conflict("幂等导入任务不存在"));
        accessService.requireCollege(actor, existing.getCollegeId());
        return toImportVO(existing, repository.listImportRows(importId));
    }

    /** 一行的解析和注册过程；行号及结果落库由外围循环统一执行。 */
    private RegisteredPerson importRow(AccountPrincipal actor, Long operationId, Long collegeId,
                                       Long academicYearId, String personType, String sourceName,
                                       int expectedColumnCount, List<String> cells, Set<String> fileKeys) {
        if (isBlankRow(cells)) {
            throw rowFailure("ROW_EMPTY", "该行没有人员数据");
        }
        if (cells.size() > expectedColumnCount) {
            throw rowFailure("COLUMN_COUNT_INVALID", "该行包含模板外的列");
        }

        List<String> values = normalizedCells(cells, expectedColumnCount);
        // 学号或工号是稳定人员标识；模板登录标识必须与之保持一致。
        String identifier = values.get(1);
        String login = values.get(0);
        String identityKey = personType + "\u0000" + identifier;
        if (!fileKeys.add(identityKey) || repository.personIdentifierExists(personType, identifier)) {
            throw rowFailure("DUPLICATE_PERSON_IDENTIFIER", "学号或工号已经存在或在本文件中重复");
        }
        if (repository.loginIdentifierExists(login)) {
            throw rowFailure("DUPLICATE_LOGIN_IDENTIFIER", "登录标识已经存在");
        }

        EligibilityInput eligibility = parseEligibility(values, personType, academicYearId, sourceName);
        // 长名单可能处理较久；每一行创建前重新读取权限，确保授权撤销后不继续创建人员账号。
        AccountPrincipal rowActor = accessService.requireCollege(actor, collegeId);
        return registerImportedPerson(rowActor, operationId, collegeId, personType, values, eligibility);
    }

    /** 把逐行业务异常落为 REJECTED 结果；历史记录不会保存或返回任何凭证明文。 */
    private PersonnelImportRowVO rejectedRow(Long importId, int rowNumber, List<String> cells,
                                             String personType, String errorCode, String errorMessage) {
        String identifier = safeCell(cells, 1);
        repository.insertImportRow(importId, rowNumber, identifier, "REJECTED", errorCode, errorMessage,
            null, null, "TEACHER".equals(personType));
        return new PersonnelImportRowVO(rowNumber, identifier, "REJECTED", errorCode, errorMessage,
            null, null, safeCell(cells, 0), null);
    }

    /** 首次导入响应携带刚生成的临时凭证明文；该字段不会进入数据库导入行记录。 */
    private static PersonnelImportRowVO createdRow(int rowNumber, List<String> cells, RegisteredPerson person) {
        CredentialResult credential = person.getCredential();
        return new PersonnelImportRowVO(rowNumber, safeCell(cells, 1), "CREATED", null, null,
            person.getPersonId(), person.getEligibilityId(), person.getLogin(), credential.getPlaintext());
    }

    @Override
    @Transactional(readOnly = true)
    public PersonnelImportVO getImport(AccountPrincipal actor, Long importId) {
        PersonnelImportEntity record = repository.findImport(importId)
            .orElseThrow(() -> notFound("导入记录不存在"));
        accessService.requireCollege(actor, record.getCollegeId());
        return toImportVO(record, null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PersonnelImportRowVO> getImportRows(AccountPrincipal actor, Long importId) {
        PersonnelImportEntity record = repository.findImport(importId)
            .orElseThrow(() -> notFound("导入记录不存在"));
        accessService.requireCollege(actor, record.getCollegeId());
        return toImportRowVOs(repository.listImportRows(importId));
    }

    private RegisteredPerson registerImportedPerson(AccountPrincipal actor, Long operationId, Long collegeId,
                                                     String type, List<String> values,
                                                     EligibilityInput eligibility) {
        if ("STUDENT".equals(type)) {
            CreateStudentRequest request = new CreateStudentRequest();
            request.setLoginIdentifier(values.get(0));
            request.setStudentNo(values.get(1));
            request.setFullName(values.get(2));
            request.setCollegeId(collegeId);
            request.setMajorCode(values.get(3));
            request.setDegreeType(values.get(4));
            request.setEnrollmentYearCode(values.get(5));
            request.setClassificationBasis("名单导入：" + eligibility.sourceName);
            request.setClassificationReason("按学院管理员导入的年度人员名单建立初始分类");
            return registrationService.createStudent(actor, operationId, request, eligibility);
        }

        CreateTeacherRequest request = new CreateTeacherRequest();
        request.setLoginIdentifier(values.get(0));
        request.setEmployeeNo(values.get(1));
        request.setFullName(values.get(2));
        request.setCollegeId(collegeId);
        return registrationService.createTeacher(actor, operationId, request, eligibility);
    }

    private EligibilityInput parseEligibility(List<String> values, String personType,
                                             Long academicYearId, String sourceName) {
        int statusIndex = "STUDENT".equals(personType) ? 6 : 3;
        int evidenceIndex = statusIndex + 1;
        int referenceIndex = statusIndex + 2;
        String status = values.get(statusIndex).toUpperCase(java.util.Locale.ROOT);
        String evidence = values.get(evidenceIndex);
        if (!"ELIGIBLE".equals(status) && !"INELIGIBLE".equals(status)) {
            throw rowFailure("ELIGIBILITY_STATUS_INVALID", "资格状态须填写 ELIGIBLE 或 INELIGIBLE");
        }
        if (evidence.isEmpty()) {
            throw rowFailure("EVIDENCE_TYPE_REQUIRED", "资格依据类型不能为空");
        }
        return new EligibilityInput(academicYearId, status, evidence, values.get(referenceIndex), sourceName);
    }

    /** 将导入任务实体转成 API 视图；行明细只从安全字段映射，不会查询凭证哈希或明文。 */
    private PersonnelImportVO toImportVO(PersonnelImportEntity entity,
                                        List<PersonnelImportRowEntity> rowEntities) {
        List<PersonnelImportRowVO> rows = rowEntities == null ? null : toImportRowVOs(rowEntities);
        return new PersonnelImportVO(entity.getId(), entity.getPersonType(), entity.getStatus(),
            entity.getAccepted(), entity.getRejected(), rows);
    }

    private List<PersonnelImportRowVO> toImportRowVOs(List<PersonnelImportRowEntity> entities) {
        List<PersonnelImportRowVO> result = new ArrayList<PersonnelImportRowVO>(entities.size());
        for (PersonnelImportRowEntity entity : entities) {
            result.add(new PersonnelImportRowVO(entity.getRowNumber(), entity.getPersonIdentifier(),
                entity.getStatus(), entity.getErrorCode(), entity.getErrorMessage(), entity.getPersonId(),
                entity.getEligibilityId(), null, null));
        }
        return result;
    }

    private Optional<PersonnelOperationEntity> replay(Long actorId, String action,
                                                      String requestId, String fingerprint) {
        Optional<PersonnelOperationEntity> found = repository.findOperation(actorId, action, requestId);
        if (!found.isPresent()) {
            return Optional.empty();
        }
        PersonnelOperationEntity existing = found.get();
        if (!fingerprint.equals(existing.getFingerprint())) {
            throw new ApiException("IDEMPOTENCY_KEY_REUSED", "幂等键已用于不同请求", HttpStatus.CONFLICT);
        }
        if (!"OK".equals(existing.getResultCode())) {
            throw new ApiException("REQUEST_IN_PROGRESS", "相同幂等请求仍在处理或已失败，请使用新的请求键",
                HttpStatus.CONFLICT);
        }
        return found;
    }

    private static List<String> headers(String personType) {
        if ("STUDENT".equals(personType)) {
            return STUDENT_HEADERS;
        }
        if ("TEACHER".equals(personType)) {
            return TEACHER_HEADERS;
        }
        throw invalid("人员类型只能是 STUDENT 或 TEACHER");
    }

    private static void validateHeaders(List<String> actual, List<String> expected) {
        List<String> normalized = new ArrayList<String>(actual.size());
        for (String cell : actual) {
            normalized.add(cell == null ? "" : cell.trim());
        }
        while (!normalized.isEmpty() && normalized.get(normalized.size() - 1).isEmpty()) {
            normalized.remove(normalized.size() - 1);
        }
        if (!expected.equals(normalized)) {
            throw invalid("名单表头与系统模板不一致，请下载最新固定模板后重新填写");
        }
    }

    private static List<String> normalizedCells(List<String> input, int expectedCount) {
        List<String> values = new ArrayList<String>(input);
        while (values.size() < expectedCount) {
            values.add("");
        }
        for (int index = 0; index < values.size(); index++) {
            String value = values.get(index);
            values.set(index, value == null ? "" : value.trim());
        }
        return values;
    }

    private static boolean isBlankRow(List<String> cells) {
        for (String cell : cells) {
            if (cell != null && !cell.trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static String safeCell(List<String> cells, int index) {
        return cells.size() > index ? trim(cells.get(index)) : "(空)";
    }

    private static String safeError(RuntimeException exception) {
        if (exception instanceof ApiException) {
            return exception.getMessage();
        }
        if (exception instanceof DuplicateKeyException) {
            return "登录标识、学号或工号与已有记录冲突";
        }
        if (exception instanceof IllegalArgumentException) {
            return exception.getMessage();
        }
        return "该行数据未能创建，请检查账号、人员标识和专业代码是否重复或有效";
    }

    private static String errorCode(RuntimeException exception) {
        if (exception instanceof DuplicateKeyException) {
            return "DUPLICATE_VALUE";
        }
        if (exception instanceof ApiException) {
            return ((ApiException) exception).getCode();
        }
        if (exception instanceof IllegalArgumentException) {
            return "ROW_VALUE_INVALID";
        }
        return "ROW_CREATE_FAILED";
    }

    private static RowImportException rowFailure(String code, String message) {
        return new RowImportException(code, message);
    }

    private static String requireIdempotencyKey(String value) {
        String key = trim(value);
        if (!UUID_V4.matcher(key).matches()) {
            throw invalid("Idempotency-Key 必须是 UUID v4 格式");
        }
        return key.toLowerCase(java.util.Locale.ROOT);
    }

    private String json(Object... values) {
        try {
            Map<String, Object> fields = new LinkedHashMap<String, Object>();
            for (int index = 0; index < values.length; index += 2) {
                fields.put(String.valueOf(values[index]), values[index + 1]);
            }
            return objectMapper.writeValueAsString(fields);
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成导入审计快照", exception);
        }
    }

    private static String sha256(byte[] content) {
        return hashBytes(content);
    }

    private static String hash(String value) {
        return hashBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String hashBytes(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("JVM does not provide SHA-256", exception);
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static ApiException invalid(String message) {
        return new ApiException("INVALID_ARGUMENT", message, HttpStatus.BAD_REQUEST);
    }

    private static ApiException conflict(String message) {
        return new ApiException("STATE_CONFLICT", message, HttpStatus.CONFLICT);
    }

    private static ApiException notFound(String message) {
        return new ApiException("NOT_FOUND", message, HttpStatus.NOT_FOUND);
    }

    private static final class RowImportException extends RuntimeException {
        private final String code;

        private RowImportException(String code, String message) {
            super(message);
            this.code = code;
        }

        private String getCode() {
            return code;
        }
    }
}
