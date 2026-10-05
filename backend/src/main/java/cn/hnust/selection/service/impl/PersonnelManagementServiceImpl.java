package cn.hnust.selection.service.impl;

import cn.hnust.selection.entity.AcademicYearEntity;
import cn.hnust.selection.entity.AnnualEligibilityEntity;
import cn.hnust.selection.entity.CollegeEntity;
import cn.hnust.selection.entity.MajorEntity;
import cn.hnust.selection.entity.PersonnelEntity;
import cn.hnust.selection.entity.PersonnelOperationEntity;
import cn.hnust.selection.exception.ApiException;
import cn.hnust.selection.repository.PersonnelManagementRepository;
import cn.hnust.selection.request.CreateMajorRequest;
import cn.hnust.selection.request.CreateStudentRequest;
import cn.hnust.selection.request.CreateTeacherRequest;
import cn.hnust.selection.request.SetAnnualEligibilityRequest;
import cn.hnust.selection.request.UpdateMajorRequest;
import cn.hnust.selection.security.AccountAuthorization;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.PersonnelAccessService;
import cn.hnust.selection.service.PersonnelManagementService;
import cn.hnust.selection.service.PersonnelRegistrationService;
import cn.hnust.selection.service.PersonnelRegistrationService.CredentialResult;
import cn.hnust.selection.service.PersonnelRegistrationService.RegisteredPerson;
import cn.hnust.selection.vo.AcademicYearOptionVO;
import cn.hnust.selection.vo.AdminAccountCredentialVO;
import cn.hnust.selection.vo.AnnualEligibilityVO;
import cn.hnust.selection.vo.CollegeOptionVO;
import cn.hnust.selection.vo.MajorVO;
import cn.hnust.selection.vo.PageVO;
import cn.hnust.selection.vo.PersonnelCreatedVO;
import cn.hnust.selection.vo.PersonnelPersonVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 人员管理领域的应用服务实现。
 *
 * <p>此类编排专业目录、人员档案和年度资格用例，并把规则交给 Repository 与人员注册服务执行。
 * Controller 只绑定 HTTP 请求；名单导入和会话权限刷新分别由独立 Service 负责。</p>
 */
@Service
public class PersonnelManagementServiceImpl implements PersonnelManagementService {
    private static final String COLLEGE_ADMIN = "COLLEGE_ADMIN";
    private static final String ADMIN_ACCOUNT_MANAGER = "ADMIN_ACCOUNT_MANAGER";
    private static final Pattern UUID_V4 = Pattern.compile(
        "(?i)^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    private final PersonnelManagementRepository repository;
    private final PersonnelAccessService accessService;
    private final PersonnelRegistrationService registrationService;
    private final ObjectMapper objectMapper;

    public PersonnelManagementServiceImpl(PersonnelManagementRepository repository,
                                          PersonnelAccessService accessService,
                                          PersonnelRegistrationService registrationService,
                                          ObjectMapper objectMapper) {
        this.repository = repository;
        this.accessService = accessService;
        this.registrationService = registrationService;
        this.objectMapper = objectMapper;
    }

    /** 普通管理员只看获授学院；总管理员可从目录中选择全系统所有已启用学院。 */
    @Override
    @Transactional(readOnly = true)
    public List<CollegeOptionVO> listAuthorizedColleges(AccountPrincipal actor) {
        AccountPrincipal current = accessService.refreshActor(actor);
        if (hasCapability(current, ADMIN_ACCOUNT_MANAGER)) {
            List<CollegeOptionVO> colleges = new ArrayList<CollegeOptionVO>();
            for (CollegeEntity college : repository.listActiveColleges()) {
                colleges.add(toCollegeVO(college));
            }
            return colleges;
        }
        Set<Long> ids = new LinkedHashSet<Long>();
        for (AccountAuthorization authorization : current.getAuthorizations()) {
            if (COLLEGE_ADMIN.equals(authorization.getCapabilityCode()) && authorization.getBatchId() == null) {
                ids.add(authorization.getCollegeId());
            }
        }
        List<CollegeOptionVO> colleges = new ArrayList<CollegeOptionVO>();
        for (Long id : ids) {
            repository.findCollege(id).ifPresent(college -> colleges.add(toCollegeVO(college)));
        }
        return colleges;
    }

    /** 按账号刷新后的能力快照识别总管理员，不以用户名、请求参数或前端状态作判断。 */
    private boolean hasCapability(AccountPrincipal actor, String capabilityCode) {
        for (AccountAuthorization authorization : actor.getAuthorizations()) {
            if (capabilityCode.equals(authorization.getCapabilityCode())) return true;
        }
        return false;
    }

    @Override
    @Transactional(readOnly = true)
    public List<MajorVO> listPublicMajors(Long collegeId) {
        if (collegeId != null && !repository.findCollege(collegeId).isPresent()) throw notFound("学院不存在或已停用");
        return toMajorVOs(repository.listPublicMajors(collegeId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AcademicYearOptionVO> listAcademicYears(AccountPrincipal actor, Long collegeId) {
        accessService.requireCollege(actor, collegeId);
        List<AcademicYearOptionVO> years = new ArrayList<AcademicYearOptionVO>();
        for (AcademicYearEntity year : repository.listAcademicYears()) {
            years.add(new AcademicYearOptionVO(year.getId(), year.getYearCode(), year.getDisplayName()));
        }
        return years;
    }

    @Override
    @Transactional(readOnly = true)
    public List<MajorVO> listMajors(AccountPrincipal actor, Long collegeId, Boolean activeOnly) {
        accessService.requireCollege(actor, collegeId);
        return toMajorVOs(repository.listMajors(collegeId, activeOnly));
    }

    /** 新增专业代码在学院内唯一；数据层唯一键负责处理并发重复新增。 */
    @Override
    @Transactional
    public MajorVO createMajor(AccountPrincipal actor, CreateMajorRequest request, String idempotencyKey) {
        accessService.requireCollege(actor, request.getCollegeId());
        String key = requireIdempotencyKey(idempotencyKey);
        String action = "MAJOR_CREATE";
        String fingerprint = fingerprint(action, request);
        Optional<PersonnelOperationEntity> replay = replay(actor.getAccountId(), action, key, fingerprint);
        if (replay.isPresent()) return repository.findMajorByCode(request.getCollegeId(), trim(request.getMajorCode()))
            .map(this::toMajorVO).orElseThrow(() -> conflict("专业创建记录未能恢复"));
        Long opId = repository.insertOperation(actor.getAccountId(), action, request.getCollegeId(), key, fingerprint);
        try {
            java.sql.Date validFrom = parseDate(request.getValidFrom(), "生效日期");
            java.sql.Date validTo = parseDate(request.getValidTo(), "失效日期");
            validateDateRange(validFrom, validTo);
            Long majorId = repository.insertMajor(request.getCollegeId(), trim(request.getMajorCode()),
                trim(request.getName()), validFrom, validTo, trim(request.getChangeBasis()));
            MajorVO result = repository.findMajor(majorId).map(this::toMajorVO)
                .orElseThrow(() -> conflict("专业记录创建后无法读取"));
            repository.insertAudit(opId, actor.getAccountId(), request.getCollegeId(), "MAJOR", majorId,
                action, json("majorCode", result.getMajorCode(), "name", result.getName(), "active", true),
                request.getChangeBasis());
            repository.completeOperation(opId);
            return result;
        } catch (DuplicateKeyException exception) {
            throw conflict("该学院已存在相同专业代码");
        }
    }

    /** 专业记录保留历史引用，通过停用实现退出目录，不物理删除。 */
    @Override
    @Transactional
    public MajorVO updateMajor(AccountPrincipal actor, Long majorId, UpdateMajorRequest request,
                                     String idempotencyKey) {
        MajorEntity currentMajor = repository.findMajor(majorId).orElseThrow(() -> notFound("专业不存在"));
        accessService.requireCollege(actor, currentMajor.getCollegeId());
        String key = requireIdempotencyKey(idempotencyKey);
        String action = "MAJOR_UPDATE";
        String fingerprint = fingerprint(action, majorId, request);
        if (replay(actor.getAccountId(), action, key, fingerprint).isPresent()) {
            return repository.findMajor(majorId).map(this::toMajorVO)
                .orElseThrow(() -> notFound("专业不存在"));
        }
        Long opId = repository.insertOperation(actor.getAccountId(), action, currentMajor.getCollegeId(), key, fingerprint);
        java.sql.Date validFrom = parseDate(request.getValidFrom(), "生效日期");
        java.sql.Date validTo = parseDate(request.getValidTo(), "失效日期");
        validateDateRange(validFrom, validTo);
        repository.updateMajor(majorId, trim(request.getName()), request.getActive().booleanValue(),
            validFrom, validTo, trim(request.getChangeBasis()));
        MajorVO updated = repository.findMajor(majorId).map(this::toMajorVO)
            .orElseThrow(() -> notFound("专业不存在"));
        repository.insertAudit(opId, actor.getAccountId(), updated.getCollegeId(), "MAJOR", majorId,
            action, json("name", updated.getName(), "active", updated.isActive(), "validFrom", updated.getValidFrom(),
                "validTo", updated.getValidTo()), request.getChangeBasis());
        repository.completeOperation(opId);
        return updated;
    }

    @Override
    @Transactional(readOnly = true)
    public PageVO<PersonnelPersonVO> listStudents(AccountPrincipal actor, Long collegeId, String identifier,
                                                               int pageNo, int pageSize) {
        requirePage(pageNo, pageSize);
        accessService.requireCollege(actor, collegeId);
        long offset = ((long) pageNo - 1L) * pageSize;
        List<PersonnelPersonVO> items = toPersonnelVOs(
            repository.listStudents(collegeId, identifier, pageSize, offset));
        return new PageVO<PersonnelPersonVO>(items, repository.countStudents(collegeId, identifier), pageNo, pageSize);
    }

    @Override
    @Transactional(readOnly = true)
    public PageVO<PersonnelPersonVO> listTeachers(AccountPrincipal actor, Long collegeId, String identifier,
                                                                int pageNo, int pageSize) {
        requirePage(pageNo, pageSize);
        accessService.requireCollege(actor, collegeId);
        long offset = ((long) pageNo - 1L) * pageSize;
        List<PersonnelPersonVO> items = toPersonnelVOs(
            repository.listTeachers(collegeId, identifier, pageSize, offset));
        return new PageVO<PersonnelPersonVO>(items, repository.countTeachers(collegeId, identifier), pageNo, pageSize);
    }

    /**
     * 创建学生账号的应用编排。
     *
     * <p>业务操作先独立提交，注册服务再通过新事务写账号、档案和一次性凭证，以便凭证表外键能看到
     * 已提交操作行。凭证明文只会进入本次响应；相同幂等键再次请求不返回明文。</p>
     */
    @Override
    public PersonnelCreatedVO createStudent(AccountPrincipal actor, CreateStudentRequest request,
                                                   String idempotencyKey) {
        accessService.requireCollege(actor, request.getCollegeId());
        String key = requireIdempotencyKey(idempotencyKey);
        String action = "STUDENT_ACCOUNT_CREATE";
        String fingerprint = fingerprint(action, request);
        Optional<PersonnelOperationEntity> replay = replay(actor.getAccountId(), action, key, fingerprint);
        if (replay.isPresent()) return replayCreatedPerson("STUDENT", request.getLoginIdentifier());
        Long opId = repository.insertOperation(actor.getAccountId(), action, request.getCollegeId(), key, fingerprint);
        try {
            RegisteredPerson created = registrationService.createStudent(actor, opId, request, null);
            repository.completeOperation(opId);
            return toCreatedResponse(created);
        } catch (RuntimeException exception) {
            repository.failOperation(opId);
            throw translateDuplicate(exception);
        }
    }

    @Override
    public PersonnelCreatedVO createTeacher(AccountPrincipal actor, CreateTeacherRequest request,
                                                   String idempotencyKey) {
        accessService.requireCollege(actor, request.getCollegeId());
        String key = requireIdempotencyKey(idempotencyKey);
        String action = "TEACHER_ACCOUNT_CREATE";
        String fingerprint = fingerprint(action, request);
        Optional<PersonnelOperationEntity> replay = replay(actor.getAccountId(), action, key, fingerprint);
        if (replay.isPresent()) return replayCreatedPerson("TEACHER", request.getLoginIdentifier());
        Long opId = repository.insertOperation(actor.getAccountId(), action, request.getCollegeId(), key, fingerprint);
        try {
            RegisteredPerson created = registrationService.createTeacher(actor, opId, request, null);
            repository.completeOperation(opId);
            return toCreatedResponse(created);
        } catch (RuntimeException exception) {
            repository.failOperation(opId);
            throw translateDuplicate(exception);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<AnnualEligibilityVO> listAnnualEligibility(AccountPrincipal actor, Long collegeId,
                                                                  Long academicYearId, String personType,
                                                                  String identifier, boolean history) {
        accessService.requireCollege(actor, collegeId);
        if (personType != null && !"STUDENT".equals(personType) && !"TEACHER".equals(personType)) {
            throw invalid("人员类型只能是 STUDENT 或 TEACHER");
        }
        if (academicYearId != null && !repository.academicYearExists(academicYearId)) throw notFound("学年不存在");
        return toEligibilityVOs(
            repository.listEligibility(collegeId, academicYearId, personType, identifier, history));
    }

    /**
     * 资格变更始终追加历史行；行锁和唯一当前槽位共同保证同一人同一学年只有一条有效版本。
     */
    @Override
    @Transactional
    public AnnualEligibilityVO setAnnualEligibility(AccountPrincipal actor, SetAnnualEligibilityRequest request,
                                                           String idempotencyKey) {
        AccountPrincipal current = accessService.requireCollege(actor, request.getCollegeId());
        if (!repository.academicYearExists(request.getAcademicYearId())) throw notFound("学年不存在");
        String key = requireIdempotencyKey(idempotencyKey);
        String action = "ANNUAL_ELIGIBILITY_SET";
        String fingerprint = fingerprint(action, request);
        if (replay(current.getAccountId(), action, key, fingerprint).isPresent()) {
            return repository.listEligibility(request.getCollegeId(), request.getAcademicYearId(),
                request.getPersonType(), null, true).stream()
                .filter(item -> item.getPersonId().equals(request.getPersonId())).findFirst()
                .map(this::toEligibilityVO)
                .orElseThrow(() -> conflict("资格记录已处理但当前历史无法读取"));
        }
        Long opId = repository.insertOperation(current.getAccountId(), action, request.getCollegeId(), key, fingerprint);
        repository.lockPerson(request.getPersonType(), request.getPersonId(), request.getCollegeId());
        Optional<Long> priorId = repository.currentEligibilityId(request.getAcademicYearId(),
            request.getPersonType(), request.getPersonId());
        priorId.ifPresent(repository::closeEligibility);
        Long eligibilityId = repository.insertEligibility(request.getAcademicYearId(), request.getCollegeId(),
            request.getPersonType(), request.getPersonId(), request.getEligibilityStatus(),
            trim(request.getEvidenceType()), trim(request.getEvidenceReference()), trim(request.getSourceName()),
            current.getAccountId());
        repository.claimEligibilitySlot(request.getAcademicYearId(), request.getCollegeId(),
            request.getPersonType(), request.getPersonId(), eligibilityId);
        repository.insertAudit(opId, current.getAccountId(), request.getCollegeId(), "ANNUAL_ELIGIBILITY",
            eligibilityId, action, json("personType", request.getPersonType(), "personId", request.getPersonId(),
                "academicYearId", request.getAcademicYearId(), "status", request.getEligibilityStatus()),
            request.getEvidenceReference());
        repository.completeOperation(opId);
        return repository.listEligibility(request.getCollegeId(), request.getAcademicYearId(),
            request.getPersonType(), null, true).stream()
            .filter(item -> item.getId().equals(eligibilityId))
            .map(this::toEligibilityVO)
            .findFirst()
            .orElseThrow(() -> conflict("资格记录创建后无法读取"));
    }

    private PersonnelCreatedVO toCreatedResponse(RegisteredPerson person) {
        CredentialResult c = person.getCredential();
        AdminAccountCredentialVO credential = new AdminAccountCredentialVO(person.getAccountId(),
            person.getLogin(), "CREATED", c.getPlaintext(), c.getExpiresAt(), true);
        return new PersonnelCreatedVO(person.getType(), person.getPersonId(), person.getAccountId(),
            person.getLogin(), credential);
    }

    private PersonnelCreatedVO replayCreatedPerson(String type, String login) {
        PersonnelEntity person = repository.findPersonByLogin(type, trim(login))
            .orElseThrow(() -> conflict("幂等创建记录对应的人员档案不存在"));
        AdminAccountCredentialVO noSecret = new AdminAccountCredentialVO(person.getAccountId(),
            person.getLoginIdentifier(), "CREATED", null, null, false);
        return new PersonnelCreatedVO(type, person.getId(), person.getAccountId(),
            person.getLoginIdentifier(), noSecret);
    }

    private Optional<PersonnelOperationEntity> replay(Long actorId, String action, String key, String fingerprint) {
        Optional<PersonnelOperationEntity> found = repository.findOperation(actorId, action, key);
        if (!found.isPresent()) return Optional.empty();
        PersonnelOperationEntity existing = found.get();
        if (!fingerprint.equals(existing.getFingerprint())) throw new ApiException("IDEMPOTENCY_KEY_REUSED",
            "幂等键已用于不同请求", HttpStatus.CONFLICT);
        if (!"OK".equals(existing.getResultCode())) throw new ApiException("REQUEST_IN_PROGRESS",
            "相同幂等请求仍在处理或已失败，请使用新的请求键", HttpStatus.CONFLICT);
        return found;
    }

    /** Repository 实体转换为 API 学院视图，避免持久化模型直接穿过 Service 接口。 */
    private static CollegeOptionVO toCollegeVO(CollegeEntity entity) {
        return new CollegeOptionVO(entity.getId(), entity.getCode(), entity.getName());
    }

    /** Repository 实体转换为专业目录视图，并在边界处格式化 SQL 日期。 */
    private MajorVO toMajorVO(MajorEntity entity) {
        String validFrom = entity.getValidFrom() == null ? null : entity.getValidFrom().toString();
        String validTo = entity.getValidTo() == null ? null : entity.getValidTo().toString();
        return new MajorVO(entity.getId(), entity.getCollegeId(), entity.getMajorCode(), entity.getName(),
            entity.isActive(), validFrom, validTo, entity.getRowVersion());
    }

    private List<MajorVO> toMajorVOs(List<MajorEntity> entities) {
        List<MajorVO> result = new ArrayList<MajorVO>(entities.size());
        for (MajorEntity entity : entities) result.add(toMajorVO(entity));
        return result;
    }

    /** 将必要的人员查询字段映射到不含凭证和隐私资料的 API 视图。 */
    private List<PersonnelPersonVO> toPersonnelVOs(List<PersonnelEntity> entities) {
        List<PersonnelPersonVO> result = new ArrayList<PersonnelPersonVO>(entities.size());
        for (PersonnelEntity entity : entities) {
            result.add(new PersonnelPersonVO(entity.getId(), entity.getAccountId(), entity.getLoginIdentifier(),
                entity.getIdentifier(), entity.getFullName(), entity.getCollegeId(), entity.getCollegeName(),
                entity.getMajorCode(), entity.getMajorName(), entity.getDegreeType(), entity.getEnrollmentYearCode(),
                entity.getClassificationVersion(), entity.getProfileReviewStatus()));
        }
        return result;
    }

    /** 学年资格实体转换为只读 API 视图。 */
    private AnnualEligibilityVO toEligibilityVO(AnnualEligibilityEntity entity) {
        return new AnnualEligibilityVO(entity.getId(), entity.getAcademicYearId(), entity.getYearCode(),
            entity.getCollegeId(), entity.getPersonType(), entity.getPersonId(), entity.getPersonIdentifier(),
            entity.getPersonName(), entity.getStatus(), entity.getEvidenceType(), entity.getEvidenceReference(),
            entity.getSourceName(), entity.getValidFrom(), entity.getValidTo(), entity.getChangedBy());
    }

    private List<AnnualEligibilityVO> toEligibilityVOs(List<AnnualEligibilityEntity> entities) {
        List<AnnualEligibilityVO> result = new ArrayList<AnnualEligibilityVO>(entities.size());
        for (AnnualEligibilityEntity entity : entities) result.add(toEligibilityVO(entity));
        return result;
    }

    private static String requireIdempotencyKey(String value) {
        String key = trim(value);
        if (!UUID_V4.matcher(key).matches()) {
            throw invalid("Idempotency-Key 必须是 UUID v4 格式");
        }
        return key.toLowerCase(java.util.Locale.ROOT);
    }

    private static void requirePage(int pageNo, int pageSize) {
        if (pageNo < 1 || pageSize < 1 || pageSize > 100) {
            throw invalid("页码须大于 0，每页数量须为 1 至 100");
        }
    }

    private static java.sql.Date parseDate(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return java.sql.Date.valueOf(LocalDate.parse(value.trim()));
        } catch (Exception exception) {
            throw invalid(label + "须使用 YYYY-MM-DD 格式");
        }
    }

    private static void validateDateRange(java.sql.Date from, java.sql.Date to) {
        if (from != null && to != null && to.before(from)) {
            throw invalid("失效日期不能早于生效日期");
        }
    }

    private String fingerprint(String action, Object... fields) {
        try {
            return hash(action + "\u0000" + objectMapper.writeValueAsString(fields));
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成请求指纹", exception);
        }
    }

    private static String hash(String value) {
        return hashBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String hashBytes(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("JVM does not provide SHA-256", exception);
        }
    }

    private String json(Object... values) {
        try {
            Map<String, Object> fields = new LinkedHashMap<String, Object>();
            for (int index = 0; index < values.length; index += 2) {
                fields.put(String.valueOf(values[index]), values[index + 1]);
            }
            return objectMapper.writeValueAsString(fields);
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成审计快照", exception);
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static RuntimeException translateDuplicate(RuntimeException exception) {
        if (exception instanceof DuplicateKeyException) {
            return conflict("登录标识、学号或工号已被使用");
        }
        return exception;
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
}
