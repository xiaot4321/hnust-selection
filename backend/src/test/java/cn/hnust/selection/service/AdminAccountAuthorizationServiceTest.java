package cn.hnust.selection.service;

import cn.hnust.selection.enums.AdminAuthorizationStatusFilter;
import cn.hnust.selection.exception.ApiException;
import cn.hnust.selection.repository.AccountRepository;
import cn.hnust.selection.repository.AccountRepository.AccountRecord;
import cn.hnust.selection.repository.AdminAccountAuthorizationRepository;
import cn.hnust.selection.repository.AdminAccountAuthorizationRepository.AuthorizationRecord;
import cn.hnust.selection.repository.AdminAccountAuthorizationRepository.ExistingOperation;
import cn.hnust.selection.request.GrantAdminAuthorizationRequest;
import cn.hnust.selection.request.RevokeAdminAuthorizationRequest;
import cn.hnust.selection.response.AdminAuthorizationCommandResponse;
import cn.hnust.selection.security.AccountAuthorization;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.impl.AccountAuthorizationServiceImpl;
import cn.hnust.selection.service.impl.AdminAccountAuthorizationServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理员授权 Service 用例测试。
 *
 * <p>这些测试用 Mockito 隔离数据库访问，重点验证 Service 的业务边界和事务内编排：
 * 只有总管理员能处理其他管理员、能力代码必须在目录内、范围和批次必须匹配、
 * 审计/幂等成功路径完整，以及重复命令不会再次产生授权写入。</p>
 */
class AdminAccountAuthorizationServiceTest {
    private static final Long ACTOR_ID = 1L;
    private static final Long TARGET_ID = 2L;
    private static final Long COLLEGE_ID = 7L;
    private static final String GRANT_KEY = "00000000-0000-4000-8000-000000000001";
    private static final String REVOKE_KEY = "00000000-0000-4000-8000-000000000002";

    private AccountRepository accountRepository;
    private AdminAccountAuthorizationRepository authorizationRepository;
    private AdminAccountAuthorizationService service;
    private AccountPrincipal superAdmin;
    private AccountRecord superAdminRecord;
    private AccountRecord targetAdminRecord;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        authorizationRepository = mock(AdminAccountAuthorizationRepository.class);
        superAdmin = principal(ACTOR_ID, true);
        superAdminRecord = account(ACTOR_ID, "ADMIN", "ACTIVE");
        targetAdminRecord = account(TARGET_ID, "ADMIN", "ACTIVE");
        service = new AdminAccountAuthorizationServiceImpl(accountRepository, authorizationRepository,
            new AccountAuthorizationServiceImpl(), new ObjectMapper());
    }

    @Test
    void grantsARegisteredBusinessCapabilityAndWritesAuditAndIdempotencyRecords() {
        prepareActor(true);
        prepareTargetForUpdate(targetAdminRecord);
        when(authorizationRepository.collegeExists(COLLEGE_ID)).thenReturn(true);
        when(authorizationRepository.hasActiveAuthorization(TARGET_ID, "BATCH_AUDIT", COLLEGE_ID, null))
            .thenReturn(false);
        when(authorizationRepository.findOperation(ACTOR_ID, "ADMIN_AUTHORIZATION_GRANT", GRANT_KEY))
            .thenReturn(Optional.empty());
        when(authorizationRepository.insertOperation(eq(ACTOR_ID), eq("ADMIN_AUTHORIZATION_GRANT"),
            eq(COLLEGE_ID), eq(null), eq(GRANT_KEY), any(String.class))).thenReturn(51L);
        when(authorizationRepository.insertAuthorization(TARGET_ID, COLLEGE_ID, null,
            "BATCH_AUDIT", "学院审计授权审批记录 A-01", ACTOR_ID)).thenReturn(81L);
        when(authorizationRepository.findAuthorizationById(81L)).thenReturn(Optional.of(
            record(81L, TARGET_ID, COLLEGE_ID, null, "BATCH_AUDIT", "学院审计授权审批记录 A-01",
                ACTOR_ID, null)));

        GrantAdminAuthorizationRequest request = grantRequest("BATCH_AUDIT", COLLEGE_ID, null,
            "学院审计授权审批记录 A-01");
        AdminAuthorizationCommandResponse response = service.grant(superAdmin, TARGET_ID, request, GRANT_KEY);

        assertEquals(81L, response.getAuthorizationId());
        assertEquals("GRANTED", response.getResult());
        verify(authorizationRepository).insertAuditEvent(eq(51L), eq(ACTOR_ID),
            eq("ADMIN_AUTHORIZATION_GRANT"), eq(81L), eq("collegeId=7;batchId=*"),
            eq(null), any(String.class), eq(null));
        verify(authorizationRepository).completeOperation(51L);
    }

    @Test
    void rejectsUnregisteredCapabilityCodesInsteadOfCreatingNewPermissions() {
        prepareActor(true);
        GrantAdminAuthorizationRequest request = grantRequest("BATCH_MANAGER", COLLEGE_ID, null, "授权依据");

        ApiException exception = assertThrows(ApiException.class,
            () -> service.grant(superAdmin, TARGET_ID, request, GRANT_KEY));

        assertEquals("INVALID_ARGUMENT", exception.getCode());
        verify(authorizationRepository, never()).insertAuthorization(any(), any(), any(), any(), any(), any());
    }

    @Test
    void refusesToGrantTheReservedAdminAccountManagerCapability() {
        prepareActor(true);
        GrantAdminAuthorizationRequest request = grantRequest(
            "ADMIN_ACCOUNT_MANAGER", COLLEGE_ID, null, "不得通过普通接口设置");

        ApiException exception = assertThrows(ApiException.class,
            () -> service.grant(superAdmin, TARGET_ID, request, GRANT_KEY));

        assertEquals("FORBIDDEN", exception.getCode());
        verify(authorizationRepository, never()).insertAuthorization(any(), any(), any(), any(), any(), any());
    }

    @Test
    void requiresTheManagersCapabilityInTheRequestedCollege() {
        prepareActor(false);
        GrantAdminAuthorizationRequest request = grantRequest("BATCH_AUDIT", COLLEGE_ID, null, "授权依据");

        ApiException exception = assertThrows(ApiException.class,
            () -> service.grant(superAdmin, TARGET_ID, request, GRANT_KEY));

        assertEquals("SCOPE_FORBIDDEN", exception.getCode());
        verify(authorizationRepository, never()).insertAuthorization(any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsABatchThatBelongsToAnotherCollege() {
        prepareActor(true);
        when(authorizationRepository.collegeExists(COLLEGE_ID)).thenReturn(true);
        when(authorizationRepository.findBatchCollegeId(25L)).thenReturn(Optional.of(8L));
        GrantAdminAuthorizationRequest request = grantRequest("BATCH_AUDIT", COLLEGE_ID, 25L, "授权依据");

        ApiException exception = assertThrows(ApiException.class,
            () -> service.grant(superAdmin, TARGET_ID, request, GRANT_KEY));

        assertEquals("NOT_FOUND", exception.getCode());
        verify(accountRepository, never()).findByIdForUpdate(TARGET_ID);
    }

    @Test
    void reportsDuplicateActiveAuthorizationAsAStateConflict() {
        prepareActor(true);
        prepareTargetForUpdate(targetAdminRecord);
        when(authorizationRepository.collegeExists(COLLEGE_ID)).thenReturn(true);
        when(authorizationRepository.hasActiveAuthorization(TARGET_ID, "BATCH_AUDIT", COLLEGE_ID, null))
            .thenReturn(true);
        when(authorizationRepository.findOperation(ACTOR_ID, "ADMIN_AUTHORIZATION_GRANT", GRANT_KEY))
            .thenReturn(Optional.empty());

        ApiException exception = assertThrows(ApiException.class,
            () -> service.grant(superAdmin, TARGET_ID,
                grantRequest("BATCH_AUDIT", COLLEGE_ID, null, "授权依据"), GRANT_KEY));

        assertEquals("STATE_CONFLICT", exception.getCode());
        verify(authorizationRepository, never()).insertOperation(any(), any(), any(), any(), any(), any());
    }

    @Test
    void repeatsTheOriginalGrantResultForTheSameIdempotencyKeyAndRequest() {
        prepareActor(true);
        when(authorizationRepository.collegeExists(COLLEGE_ID)).thenReturn(true);
        when(authorizationRepository.findOperation(ACTOR_ID, "ADMIN_AUTHORIZATION_GRANT", GRANT_KEY))
            .thenReturn(Optional.empty());
        prepareTargetForUpdate(targetAdminRecord);
        when(authorizationRepository.hasActiveAuthorization(TARGET_ID, "BATCH_AUDIT", COLLEGE_ID, null))
            .thenReturn(false);
        when(authorizationRepository.insertOperation(eq(ACTOR_ID), eq("ADMIN_AUTHORIZATION_GRANT"),
            eq(COLLEGE_ID), eq(null), eq(GRANT_KEY), any(String.class))).thenReturn(52L);
        when(authorizationRepository.insertAuthorization(TARGET_ID, COLLEGE_ID, null,
            "BATCH_AUDIT", "授权依据", ACTOR_ID)).thenReturn(82L);
        when(authorizationRepository.findAuthorizationById(82L)).thenReturn(Optional.of(
            record(82L, TARGET_ID, COLLEGE_ID, null, "BATCH_AUDIT", "授权依据", ACTOR_ID, null)));

        GrantAdminAuthorizationRequest request = grantRequest("BATCH_AUDIT", COLLEGE_ID, null, "授权依据");
        service.grant(superAdmin, TARGET_ID, request, GRANT_KEY);
        ArgumentCaptor<String> fingerprint = ArgumentCaptor.forClass(String.class);
        verify(authorizationRepository).insertOperation(eq(ACTOR_ID), eq("ADMIN_AUTHORIZATION_GRANT"),
            eq(COLLEGE_ID), eq(null), eq(GRANT_KEY), fingerprint.capture());
        when(authorizationRepository.findOperation(ACTOR_ID, "ADMIN_AUTHORIZATION_GRANT", GRANT_KEY))
            .thenReturn(Optional.of(new ExistingOperation(fingerprint.getValue(), "OK", 82L)));

        AdminAuthorizationCommandResponse retry = service.grant(superAdmin, TARGET_ID, request, GRANT_KEY);

        assertEquals(82L, retry.getAuthorizationId());
        assertEquals("GRANTED", retry.getResult());
        verify(authorizationRepository).insertAuthorization(TARGET_ID, COLLEGE_ID, null,
            "BATCH_AUDIT", "授权依据", ACTOR_ID);
    }

    @Test
    void rejectsReusingAnIdempotencyKeyForDifferentRequestContent() {
        prepareActor(true);
        when(authorizationRepository.collegeExists(COLLEGE_ID)).thenReturn(true);
        when(authorizationRepository.findOperation(ACTOR_ID, "ADMIN_AUTHORIZATION_GRANT", GRANT_KEY))
            .thenReturn(Optional.of(new ExistingOperation("different-request", "OK", 82L)));

        ApiException exception = assertThrows(ApiException.class,
            () -> service.grant(superAdmin, TARGET_ID,
                grantRequest("BATCH_AUDIT", COLLEGE_ID, null, "其他授权依据"), GRANT_KEY));

        assertEquals("IDEMPOTENCY_KEY_REUSED", exception.getCode());
        verify(authorizationRepository, never()).insertAuthorization(any(), any(), any(), any(), any(), any());
    }

    @Test
    void revokesAndAuditsTheExistingAuthorizationWithoutDeletingItsHistory() {
        prepareActor(true);
        prepareTargetForUpdate(targetAdminRecord);
        when(authorizationRepository.findOperation(ACTOR_ID, "ADMIN_AUTHORIZATION_REVOKE", REVOKE_KEY))
            .thenReturn(Optional.empty());
        AuthorizationRecord before = record(81L, TARGET_ID, COLLEGE_ID, 25L, "BATCH_AUDIT",
            "批次授权依据", ACTOR_ID, null);
        AuthorizationRecord after = record(81L, TARGET_ID, COLLEGE_ID, 25L, "BATCH_AUDIT",
            "批次授权依据", ACTOR_ID, ACTOR_ID);
        when(authorizationRepository.findAuthorizationForUpdate(TARGET_ID, 81L)).thenReturn(Optional.of(before));
        when(authorizationRepository.insertOperation(eq(ACTOR_ID), eq("ADMIN_AUTHORIZATION_REVOKE"),
            eq(COLLEGE_ID), eq(25L), eq(REVOKE_KEY), any(String.class))).thenReturn(53L);
        when(authorizationRepository.revokeAuthorization(81L, ACTOR_ID)).thenReturn(true);
        when(authorizationRepository.findAuthorizationById(81L)).thenReturn(Optional.of(after));

        RevokeAdminAuthorizationRequest request = revokeRequest("原授权期限届满");
        AdminAuthorizationCommandResponse response = service.revoke(
            superAdmin, TARGET_ID, 81L, request, REVOKE_KEY);

        assertEquals(81L, response.getAuthorizationId());
        assertEquals("REVOKED", response.getResult());
        verify(authorizationRepository).insertAuditEvent(eq(53L), eq(ACTOR_ID),
            eq("ADMIN_AUTHORIZATION_REVOKE"), eq(81L), eq("collegeId=7;batchId=25"),
            any(String.class), any(String.class), eq("原授权期限届满"));
        verify(authorizationRepository).completeOperation(53L);
    }

    @Test
    void cannotRevokeTheReservedAdminAccountManagerCapability() {
        prepareActor(true);
        prepareTargetForUpdate(targetAdminRecord);
        when(authorizationRepository.findOperation(ACTOR_ID, "ADMIN_AUTHORIZATION_REVOKE", REVOKE_KEY))
            .thenReturn(Optional.empty());
        when(authorizationRepository.findAuthorizationForUpdate(TARGET_ID, 81L)).thenReturn(Optional.of(
            record(81L, TARGET_ID, COLLEGE_ID, null, "ADMIN_ACCOUNT_MANAGER", "初始化设置", ACTOR_ID, null)));

        ApiException exception = assertThrows(ApiException.class,
            () -> service.revoke(superAdmin, TARGET_ID, 81L,
                revokeRequest("不能常规撤销"), REVOKE_KEY));

        assertEquals("FORBIDDEN", exception.getCode());
        verify(authorizationRepository, never()).revokeAuthorization(any(), any());
    }

    @Test
    void authorizationQueriesDefaultToActiveRecordsAndReturnHistoryFields() {
        prepareActor(true);
        when(accountRepository.findById(TARGET_ID)).thenReturn(Optional.of(targetAdminRecord));
        when(authorizationRepository.collegeExists(COLLEGE_ID)).thenReturn(true);
        AuthorizationRecord active = record(81L, TARGET_ID, COLLEGE_ID, null, "BATCH_AUDIT",
            "授权依据", ACTOR_ID, null);
        when(authorizationRepository.findAuthorizations(TARGET_ID, COLLEGE_ID, "ACTIVE"))
            .thenReturn(Collections.singletonList(active));

        assertEquals(1, service.listAuthorizations(superAdmin, TARGET_ID, COLLEGE_ID, null).size());
        verify(authorizationRepository).findAuthorizations(TARGET_ID, COLLEGE_ID, "ACTIVE");
    }

    private void prepareActor(boolean hasAccountManagerCapability) {
        AccountRecord actorRecord = account(ACTOR_ID, "ADMIN", "ACTIVE");
        when(accountRepository.findByIdForUpdate(ACTOR_ID)).thenReturn(Optional.of(actorRecord));
        when(accountRepository.findById(ACTOR_ID)).thenReturn(Optional.of(actorRecord));
        when(accountRepository.toPrincipal(actorRecord, false)).thenReturn(
            principal(ACTOR_ID, hasAccountManagerCapability));
    }

    private void prepareTargetForUpdate(AccountRecord target) {
        when(accountRepository.findByIdForUpdate(TARGET_ID)).thenReturn(Optional.of(target));
    }

    private AccountPrincipal principal(Long accountId, boolean hasAccountManagerCapability) {
        return new AccountPrincipal(accountId, "admin-" + accountId,
            cn.hnust.selection.enums.AccountRole.ADMIN, "ACTIVE", false, false,
            new Timestamp(1000L), 1L, null,
            hasAccountManagerCapability
                ? Collections.singletonList(new AccountAuthorization(COLLEGE_ID, null, "ADMIN_ACCOUNT_MANAGER"))
                : Collections.singletonList(new AccountAuthorization(COLLEGE_ID, null, "BATCH_AUDIT")));
    }

    private AccountRecord account(Long accountId, String role, String status) {
        return new AccountRecord(accountId, "admin-" + accountId, role, status,
            "unused-hash", false, new Timestamp(1000L), 1L);
    }

    private GrantAdminAuthorizationRequest grantRequest(String capabilityCode, Long collegeId,
                                                        Long batchId, String basis) {
        GrantAdminAuthorizationRequest request = new GrantAdminAuthorizationRequest();
        request.setCapabilityCode(capabilityCode);
        request.setCollegeId(collegeId);
        request.setBatchId(batchId);
        request.setBasis(basis);
        return request;
    }

    private RevokeAdminAuthorizationRequest revokeRequest(String reason) {
        RevokeAdminAuthorizationRequest request = new RevokeAdminAuthorizationRequest();
        request.setReason(reason);
        return request;
    }

    private AuthorizationRecord record(Long id, Long accountId, Long collegeId, Long batchId,
                                       String capabilityCode, String basis, Long grantedBy, Long revokedBy) {
        Timestamp grantedAt = Timestamp.valueOf("2026-10-03 08:00:00");
        Timestamp revokedAt = revokedBy == null ? null : Timestamp.valueOf("2026-10-03 09:00:00");
        return new AuthorizationRecord(id, accountId, collegeId, batchId, capabilityCode,
            null, basis, grantedBy, grantedAt, revokedBy, revokedAt,
            revokedBy == null ? null : "原授权期限届满");
    }
}
