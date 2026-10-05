package cn.hnust.selection.service.impl;

import cn.hnust.selection.exception.ApiException;
import cn.hnust.selection.entity.MajorEntity;
import cn.hnust.selection.repository.PersonnelManagementRepository;
import cn.hnust.selection.request.CreateStudentRequest;
import cn.hnust.selection.request.CreateTeacherRequest;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.PersonnelRegistrationService;
import cn.hnust.selection.service.PersonnelRegistrationService.CredentialResult;
import cn.hnust.selection.service.PersonnelRegistrationService.EligibilityInput;
import cn.hnust.selection.service.PersonnelRegistrationService.RegisteredPerson;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 单个人员档案的事务服务。
 *
 * <p>名单导入的每一行通过独立 Spring Bean 调用本服务的 REQUIRES_NEW 方法；因此一行失败回滚自身，
 * 而前后成功行可以保留并分别展示结果。账号、人员档案、资格、临时凭证及审计在同一行事务提交。</p>
 */
@Service
public class PersonnelRegistrationServiceImpl implements PersonnelRegistrationService {
    private final PersonnelManagementRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;

    public PersonnelRegistrationServiceImpl(PersonnelManagementRepository repository,
                                            PasswordEncoder passwordEncoder, ObjectMapper objectMapper) {
        this.repository = repository; this.passwordEncoder = passwordEncoder; this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RegisteredPerson createStudent(AccountPrincipal actor, Long operationId,
                                          CreateStudentRequest request, EligibilityInput eligibility) {
        String login = normalize(request.getLoginIdentifier());
        String studentNo = normalize(request.getStudentNo());
        if (!login.equals(studentNo)) throw invalid("学生登录标识必须与学号一致");
        ensureAvailable("STUDENT", login, studentNo);
        if (!repository.findCollege(request.getCollegeId()).isPresent()) throw invalid("学院不存在或已停用");
        MajorEntity major = repository.findMajorByCode(
            request.getCollegeId(), normalize(request.getMajorCode())).orElseThrow(() -> invalid("专业代码不属于该学院"));
        if (!major.isActive()) throw invalid("该专业目录项已停用，不能用于新建学生档案");

        Long accountId = repository.insertAccount(login, "STUDENT");
        Long studentId = repository.insertStudent(accountId, studentNo, normalize(request.getFullName()),
            request.getCollegeId(), major.getId(), request.getDegreeType(),
            normalize(request.getEnrollmentYearCode()));
        repository.insertInitialStudentProfile(studentId, actor.getAccountId());
        repository.insertInitialClassificationRevision(studentId, major.getId(), request.getDegreeType(),
            normalize(request.getClassificationBasis()), normalize(request.getClassificationReason()),
            actor.getAccountId());
        Long eligibilityId = createEligibilityIfRequested(actor, request.getCollegeId(), "STUDENT", studentId,
            eligibility);
        CredentialResult credential = issueCredential(accountId, studentNo, operationId);
        repository.insertAudit(operationId, actor.getAccountId(), request.getCollegeId(), "STUDENT", studentId,
            "STUDENT_ACCOUNT_CREATED", json("accountId", accountId,
                "classificationVersion", 1, "majorCode", major.getMajorCode(), "degreeType", request.getDegreeType(),
                "initialCredentialMethod", "STUDENT_NO_SUFFIX_6"),
            "学院管理员创建学生账号与初始档案");
        return new RegisteredPerson("STUDENT", studentId, accountId, login, credential, eligibilityId);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RegisteredPerson createTeacher(AccountPrincipal actor, Long operationId,
                                          CreateTeacherRequest request, EligibilityInput eligibility) {
        String login = normalize(request.getLoginIdentifier());
        String employeeNo = normalize(request.getEmployeeNo());
        if (!login.equals(employeeNo)) throw invalid("导师登录标识必须与工号一致");
        ensureAvailable("TEACHER", login, employeeNo);
        if (!repository.findCollege(request.getCollegeId()).isPresent()) throw invalid("学院不存在或已停用");

        Long accountId = repository.insertAccount(login, "TEACHER");
        Long teacherId = repository.insertTeacher(accountId, employeeNo, normalize(request.getFullName()),
            request.getCollegeId());
        // 新导师先获得空白待完善资料版本；只有后续审核通过的版本才会公开进入导师目录。
        repository.insertInitialTeacherProfile(teacherId);
        Long eligibilityId = createEligibilityIfRequested(actor, request.getCollegeId(), "TEACHER", teacherId,
            eligibility);
        CredentialResult credential = issueCredential(accountId, employeeNo, operationId);
        repository.insertAudit(operationId, actor.getAccountId(), request.getCollegeId(), "TEACHER", teacherId,
            "TEACHER_ACCOUNT_CREATED", json("accountId", accountId,
                "initialPublicProfileStatus", "DRAFT", "initialCredentialMethod", "EMPLOYEE_NO_SUFFIX_6"),
            "学院管理员创建导师账号与空白待完善资料");
        return new RegisteredPerson("TEACHER", teacherId, accountId, login, credential, eligibilityId);
    }

    private Long createEligibilityIfRequested(AccountPrincipal actor, Long collegeId, String type,
                                              Long personId, EligibilityInput input) {
        if (input == null) return null;
        if (!"ELIGIBLE".equals(input.status) && !"INELIGIBLE".equals(input.status)) {
            throw invalid("资格状态只能为 ELIGIBLE 或 INELIGIBLE");
        }
        repository.lockPerson(type, personId, collegeId);
        Long id = repository.insertEligibility(input.academicYearId, collegeId, type, personId,
            input.status, input.evidenceType, input.evidenceReference, input.sourceName, actor.getAccountId());
        repository.claimEligibilitySlot(input.academicYearId, collegeId, type, personId, id);
        return id;
    }

    /**
     * 按 TODO-58 从学号/工号末尾截取最多六个字符，作为新建学生或导师账号的首次登录密码。
     *
     * <p>短于六位的编号不补位，直接使用完整编号，确保所有有效人员编号都能生成初始密码。
     * 明文只在本次创建事务中短暂用于 BCrypt 编码和首次响应；凭证表只接收哈希，且继续遵守
     * 不设到期时间、一次性消费和首次登录强制改密规则。审计只记录凭证来源类型，不记录密码。</p>
     */
    private CredentialResult issueCredential(Long accountId, String personIdentifier, Long operationId) {
        // 人员编号已在上游去除首尾空白；保留末尾最多六位，短编号则完整保留。
        int suffixStart = Math.max(0, personIdentifier.length() - 6);
        String plaintext = personIdentifier.substring(suffixStart);
        Long credentialId = repository.insertTemporaryCredential(accountId, passwordEncoder.encode(plaintext), operationId);
        return new CredentialResult(plaintext, repository.credentialExpiry(credentialId));
    }

    private void ensureAvailable(String type, String login, String identifier) {
        if (repository.loginIdentifierExists(login)) throw conflict("登录标识已被使用");
        if (repository.personIdentifierExists(type, identifier)) {
            throw conflict("STUDENT".equals(type) ? "学号已被使用" : "工号已被使用");
        }
    }

    private String json(Object... pairs) {
        try {
            java.util.Map<String, Object> value = new java.util.LinkedHashMap<String, Object>();
            for (int i = 0; i < pairs.length; i += 2) value.put(String.valueOf(pairs[i]), pairs[i + 1]);
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) { throw new IllegalStateException("无法生成审计记录", exception); }
    }
    private static String normalize(String value) { return value == null ? "" : value.trim(); }
    private static ApiException invalid(String message) { return new ApiException("INVALID_ARGUMENT", message, HttpStatus.BAD_REQUEST); }
    private static ApiException conflict(String message) { return new ApiException("STATE_CONFLICT", message, HttpStatus.CONFLICT); }

}
