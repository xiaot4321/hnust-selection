package cn.hnust.selection.service.impl;

import cn.hnust.selection.entity.AccountEntity;
import cn.hnust.selection.entity.SelectionBatchEntity;
import cn.hnust.selection.entity.TeacherQuotaEntity;
import cn.hnust.selection.entity.TeacherScopeVersionEntity;
import cn.hnust.selection.enums.AccountRole;
import cn.hnust.selection.exception.ApiException;
import cn.hnust.selection.repository.AccountRepository;
import cn.hnust.selection.repository.SelectionBatchRepository;
import cn.hnust.selection.request.SetTeacherApplicationScopeRequest;
import cn.hnust.selection.request.SetTeacherQuotaRequest;
import cn.hnust.selection.security.AccountAuthorization;
import cn.hnust.selection.security.AccountIdentity;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.AccountAuthorizationService;
import cn.hnust.selection.vo.MajorVO;
import cn.hnust.selection.vo.TeacherApplicationScopeVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次管理核心 Service 规则测试。
 *
 * <p>通过隔离 Repository 验证服务层会执行的招生范围默认值、专业和身份边界、填报开始冻结，
 * 以及名额不得下调到已锁定关系数以下。数据库行锁和唯一约束仍需由真实 MySQL 集成测试验证。</p>
 */
class SelectionBatchManagementServiceImplTest {
    private static final Long ADMIN_ACCOUNT_ID = 10L;
    private static final Long TEACHER_ACCOUNT_ID = 20L;
    private static final Long TEACHER_ID = 200L;
    private static final Long COLLEGE_ID = 7L;
    private static final Long YEAR_ID = 12L;
    private static final Long BATCH_ID = 300L;
    private static final Long SCOPE_ID = 400L;
    private static final Long MAJOR_ONE_ID = 701L;
    private static final Long MAJOR_TWO_ID = 702L;
    private static final String QUOTA_KEY = "00000000-0000-4000-8000-000000000031";

    private SelectionBatchRepository repository;
    private AccountRepository accountRepository;
    private AccountAuthorizationService authorizationService;
    private SelectionBatchManagementServiceImpl service;
    private AccountPrincipal teacher;
    private AccountPrincipal admin;
    private AccountEntity teacherRecord;
    private AccountEntity adminRecord;

    @BeforeEach
    void setUp() {
        repository = mock(SelectionBatchRepository.class);
        accountRepository = mock(AccountRepository.class);
        authorizationService = mock(AccountAuthorizationService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        service = new SelectionBatchManagementServiceImpl(repository, accountRepository, authorizationService,
            new ObjectMapper(), transactionManager);

        teacherRecord = account(TEACHER_ACCOUNT_ID, "TEACHER");
        teacher = principal(TEACHER_ACCOUNT_ID, AccountRole.TEACHER,
            new AccountIdentity(TEACHER_ID, "导师甲", "T0001", COLLEGE_ID), Collections.<AccountAuthorization>emptyList());
        adminRecord = account(ADMIN_ACCOUNT_ID, "ADMIN");
        admin = principal(ADMIN_ACCOUNT_ID, AccountRole.ADMIN, null,
            Collections.singletonList(new AccountAuthorization(COLLEGE_ID, null, "BATCH_MANAGER")));
    }

    @Test
    void teacherCanSaveACompleteScopeBeforeFillingStarts() {
        prepareTeacherContext(true);
        when(repository.latestScope(BATCH_ID, TEACHER_ID, true)).thenReturn(Optional.<TeacherScopeVersionEntity>empty());
        when(repository.activeMajorsBelongToCollege(COLLEGE_ID, Collections.singletonList(MAJOR_ONE_ID)))
            .thenReturn(true);
        MajorVO selectedMajor = major(MAJOR_ONE_ID, "计算机科学与技术");
        when(repository.findActiveMajorsByIds(COLLEGE_ID, Collections.singletonList(MAJOR_ONE_ID)))
            .thenReturn(Collections.singletonList(selectedMajor));
        when(repository.insertScopeVersion(BATCH_ID, TEACHER_ID, 1, 1, TEACHER_ACCOUNT_ID, "TEACHER", false))
            .thenReturn(SCOPE_ID);
        when(repository.latestScope(BATCH_ID, TEACHER_ID, false)).thenReturn(Optional.of(
            scope(SCOPE_ID, 1, 1, "TEACHER", false)));
        when(repository.listScopeMajorIds(SCOPE_ID)).thenReturn(Collections.singletonList(MAJOR_ONE_ID));
        when(repository.isScopeFrozen(BATCH_ID, TEACHER_ID)).thenReturn(false);

        SetTeacherApplicationScopeRequest request = scopeRequest(
            Collections.singletonList("ACADEMIC_MASTER"), Collections.singletonList(MAJOR_ONE_ID));
        TeacherApplicationScopeVO result = service.setTeacherScope(teacher, BATCH_ID, request, 0);

        assertEquals(1, result.getVersionNo());
        assertEquals(Collections.singletonList("ACADEMIC_MASTER"), result.getAllowedDegreeTypes());
        assertEquals(Collections.singletonList(MAJOR_ONE_ID), result.getMajorIds());
        assertFalse(result.isDefaultAllApplied());
        assertFalse(result.isFrozen());
        verify(repository).insertAllowedMajors(SCOPE_ID, Collections.singletonList(selectedMajor));
        verify(repository).insertScopeAudit(eq(TEACHER_ACCOUNT_ID), eq("TEACHER"), eq(BATCH_ID), eq(SCOPE_ID),
            eq(null), any(String.class));
    }

    @Test
    void incompleteScopeDefaultsToBothDegreesAndAllActiveMajors() {
        prepareTeacherContext(true);
        when(repository.latestScope(BATCH_ID, TEACHER_ID, true)).thenReturn(Optional.<TeacherScopeVersionEntity>empty());
        MajorVO majorOne = major(MAJOR_ONE_ID, "计算机科学与技术");
        MajorVO majorTwo = major(MAJOR_TWO_ID, "软件工程");
        when(repository.listActiveMajors(COLLEGE_ID)).thenReturn(Arrays.asList(majorOne, majorTwo));
        when(repository.insertScopeVersion(BATCH_ID, TEACHER_ID, 1, 3, TEACHER_ACCOUNT_ID, "DEFAULT_ALL", true))
            .thenReturn(SCOPE_ID);
        when(repository.latestScope(BATCH_ID, TEACHER_ID, false)).thenReturn(Optional.of(
            scope(SCOPE_ID, 1, 3, "DEFAULT_ALL", true)));
        when(repository.listScopeMajorIds(SCOPE_ID)).thenReturn(Arrays.asList(MAJOR_ONE_ID, MAJOR_TWO_ID));
        when(repository.isScopeFrozen(BATCH_ID, TEACHER_ID)).thenReturn(false);

        TeacherApplicationScopeVO result = service.setTeacherScope(teacher, BATCH_ID,
            scopeRequest(Collections.<String>emptyList(), Arrays.asList(MAJOR_ONE_ID)), 0);

        assertEquals(Arrays.asList("ACADEMIC_MASTER", "PROFESSIONAL_MASTER"), result.getAllowedDegreeTypes());
        assertEquals(Arrays.asList(MAJOR_ONE_ID, MAJOR_TWO_ID), result.getMajorIds());
        assertTrue(result.isDefaultAllApplied());
        verify(repository).insertScopeVersion(BATCH_ID, TEACHER_ID, 1, 3, TEACHER_ACCOUNT_ID, "DEFAULT_ALL", true);
        verify(repository).insertAllowedMajors(SCOPE_ID, Arrays.asList(majorOne, majorTwo));
    }

    @Test
    void teacherCannotChangeScopeAfterFillingStarts() {
        prepareTeacherContext(false);

        ApiException exception = assertThrows(ApiException.class,
            () -> service.setTeacherScope(teacher, BATCH_ID,
                scopeRequest(Collections.singletonList("ACADEMIC_MASTER"), Collections.singletonList(MAJOR_ONE_ID)), 0));

        assertEquals("STATE_CONFLICT", exception.getCode());
        verify(repository, never()).latestScope(BATCH_ID, TEACHER_ID, true);
        verify(repository, never()).insertScopeVersion(any(), any(), anyInt(), anyInt(),
            any(), any(String.class), anyBoolean());
    }

    @Test
    void teacherCannotReadOrWriteScopeForABatchWithoutTheirQuotaAccount() {
        prepareTeacherContext(true);
        when(repository.teacherHasBatchQuota(BATCH_ID, TEACHER_ID)).thenReturn(false);

        ApiException exception = assertThrows(ApiException.class,
            () -> service.setTeacherScope(teacher, BATCH_ID,
                scopeRequest(Collections.singletonList("ACADEMIC_MASTER"), Collections.singletonList(MAJOR_ONE_ID)), 0));

        assertEquals("NOT_FOUND", exception.getCode());
        verify(repository, never()).scopeEditTimeOpen(BATCH_ID);
        verify(repository, never()).insertScopeVersion(any(), any(), anyInt(), anyInt(),
            any(), any(String.class), anyBoolean());
    }

    @Test
    void staleScopeVersionIsRejectedWithoutCreatingANewVersion() {
        prepareTeacherContext(true);
        when(repository.latestScope(BATCH_ID, TEACHER_ID, true)).thenReturn(Optional.of(
            scope(SCOPE_ID, 2, 1, "TEACHER", false)));

        ApiException exception = assertThrows(ApiException.class,
            () -> service.setTeacherScope(teacher, BATCH_ID,
                scopeRequest(Collections.singletonList("ACADEMIC_MASTER"), Collections.singletonList(MAJOR_ONE_ID)), 1));

        assertEquals("PRECONDITION_FAILED", exception.getCode());
        verify(repository, never()).insertScopeVersion(any(), any(), anyInt(), anyInt(),
            any(), any(String.class), anyBoolean());
    }

    @Test
    void scopeRejectsAnActiveMajorFromAnotherCollege() {
        prepareTeacherContext(true);
        when(repository.latestScope(BATCH_ID, TEACHER_ID, true)).thenReturn(Optional.<TeacherScopeVersionEntity>empty());
        when(repository.activeMajorsBelongToCollege(COLLEGE_ID, Collections.singletonList(MAJOR_TWO_ID)))
            .thenReturn(false);

        ApiException exception = assertThrows(ApiException.class,
            () -> service.setTeacherScope(teacher, BATCH_ID,
                scopeRequest(Collections.singletonList("ACADEMIC_MASTER"), Collections.singletonList(MAJOR_TWO_ID)), 0));

        assertEquals("INVALID_ARGUMENT", exception.getCode());
        verify(repository, never()).insertScopeVersion(any(), any(), anyInt(), anyInt(),
            any(), any(String.class), anyBoolean());
    }

    @Test
    void quotaCannotBeReducedBelowTheNumberOfLockedRelationships() {
        prepareAdminContext();
        when(authorizationService.hasCapability(admin, "BATCH_MANAGER", COLLEGE_ID, BATCH_ID)).thenReturn(true);
        when(repository.teacherEligibilityBasis(BATCH_ID, TEACHER_ID)).thenReturn(Optional.of("eligible"));
        when(repository.findQuotaOperation(ADMIN_ACCOUNT_ID, QUOTA_KEY))
            .thenReturn(Optional.<SelectionBatchRepository.OperationRecord>empty());
        when(repository.lockQuota(BATCH_ID, TEACHER_ID)).thenReturn(Optional.of(
            new TeacherQuotaEntity(1L, BATCH_ID, TEACHER_ID, "eligible", 4, 3, 2L)));
        SetTeacherQuotaRequest request = new SetTeacherQuotaRequest();
        request.setQuotaLimit(2);

        ApiException exception = assertThrows(ApiException.class,
            () -> service.setTeacherQuota(admin, BATCH_ID, TEACHER_ID, request, 2L, QUOTA_KEY));

        assertEquals("QUOTA_BELOW_OCCUPIED", exception.getCode());
        verify(repository, never()).updateQuota(BATCH_ID, TEACHER_ID, 2L, "eligible", 2);
        verify(repository, never()).insertQuota(eq(BATCH_ID), eq(TEACHER_ID), any(String.class), anyInt());
    }

    @Test
    void staleQuotaVersionIsRejectedBeforeChangingTheLimit() {
        prepareAdminContext();
        when(authorizationService.hasCapability(admin, "BATCH_MANAGER", COLLEGE_ID, BATCH_ID)).thenReturn(true);
        when(repository.teacherEligibilityBasis(BATCH_ID, TEACHER_ID)).thenReturn(Optional.of("eligible"));
        when(repository.findQuotaOperation(ADMIN_ACCOUNT_ID, QUOTA_KEY))
            .thenReturn(Optional.<SelectionBatchRepository.OperationRecord>empty());
        when(repository.lockQuota(BATCH_ID, TEACHER_ID)).thenReturn(Optional.of(
            new TeacherQuotaEntity(1L, BATCH_ID, TEACHER_ID, "eligible", 4, 1, 3L)));
        SetTeacherQuotaRequest request = new SetTeacherQuotaRequest();
        request.setQuotaLimit(5);

        ApiException exception = assertThrows(ApiException.class,
            () -> service.setTeacherQuota(admin, BATCH_ID, TEACHER_ID, request, 2L, QUOTA_KEY));

        assertEquals("PRECONDITION_FAILED", exception.getCode());
        verify(repository, never()).updateQuota(BATCH_ID, TEACHER_ID, 2L, "eligible", 5);
    }

    private void prepareTeacherContext(boolean editOpen) {
        when(accountRepository.findByIdForUpdate(TEACHER_ACCOUNT_ID)).thenReturn(Optional.of(teacherRecord));
        when(accountRepository.toPrincipal(teacherRecord, false)).thenReturn(teacher);
        when(repository.lockBatch(BATCH_ID)).thenReturn(Optional.of(batch("DRAFT")));
        when(repository.teacherHasBatchQuota(BATCH_ID, TEACHER_ID)).thenReturn(true);
        when(repository.scopeEditTimeOpen(BATCH_ID)).thenReturn(editOpen);
    }

    private void prepareAdminContext() {
        when(accountRepository.findByIdForUpdate(ADMIN_ACCOUNT_ID)).thenReturn(Optional.of(adminRecord));
        when(accountRepository.findById(ADMIN_ACCOUNT_ID)).thenReturn(Optional.of(adminRecord));
        when(accountRepository.toPrincipal(adminRecord, false)).thenReturn(admin);
        when(repository.lockBatch(BATCH_ID)).thenReturn(Optional.of(batch("DRAFT")));
    }

    private static SelectionBatchEntity batch(String status) {
        return new SelectionBatchEntity(BATCH_ID, COLLEGE_ID, YEAR_ID, "2026-01", "测试批次", status,
            false, null, ADMIN_ACCOUNT_ID, null, null, null, 1L);
    }

    private static AccountEntity account(Long id, String role) {
        return new AccountEntity(id, "account-" + id, role, "ACTIVE", "unused-hash", false,
            new Timestamp(1000L), 1L);
    }

    private static AccountPrincipal principal(Long accountId, AccountRole role, AccountIdentity identity,
        java.util.List<AccountAuthorization> authorizations) {
        return new AccountPrincipal(accountId, "account-" + accountId, role, "ACTIVE", false, false,
            new Timestamp(1000L), 1L, identity, authorizations);
    }

    private static TeacherScopeVersionEntity scope(Long id, int version, int degreeMask,
        String source, boolean defaultAll) {
        return new TeacherScopeVersionEntity(id, BATCH_ID, TEACHER_ID, version, degreeMask,
            TEACHER_ACCOUNT_ID, new Timestamp(2000L), null, source, defaultAll);
    }

    private static MajorVO major(Long id, String name) {
        return new MajorVO(id, COLLEGE_ID, "M" + id, name, true, null, null, 1L);
    }

    private static SetTeacherApplicationScopeRequest scopeRequest(java.util.List<String> degrees,
        java.util.List<Long> majors) {
        SetTeacherApplicationScopeRequest request = new SetTeacherApplicationScopeRequest();
        request.setAllowedDegreeTypes(degrees);
        request.setMajorIds(majors);
        return request;
    }
}
