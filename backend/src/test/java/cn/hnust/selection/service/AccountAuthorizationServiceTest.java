package cn.hnust.selection.service;

import cn.hnust.selection.enums.AccountRole;
import cn.hnust.selection.enums.AdminCapabilityCode;
import cn.hnust.selection.security.AccountAuthorization;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.impl.AccountAuthorizationServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 授权测试：角色不能替代能力，能力也不能越过学院或批次范围。 */
class AccountAuthorizationServiceTest {
    private final AccountAuthorizationService authorizationService = new AccountAuthorizationServiceImpl();

    @Test
    void adminCapabilityIsRestrictedToItsCollegeAndOptionalBatch() {
        AccountPrincipal admin = principal(AccountRole.ADMIN, Arrays.asList(
            new AccountAuthorization(7L, null, "COLLEGE_ADMIN"),
            new AccountAuthorization(7L, 22L, "BATCH_AUDIT")));

        assertTrue(authorizationService.hasCapability(admin, "COLLEGE_ADMIN", 7L, null));
        assertTrue(authorizationService.hasCapability(admin, "COLLEGE_ADMIN", 7L, 23L));
        assertFalse(authorizationService.hasCapability(admin, "COLLEGE_ADMIN", 8L, null));
        assertTrue(authorizationService.hasCapability(admin, "BATCH_AUDIT", 7L, 22L));
        assertFalse(authorizationService.hasCapability(admin, "BATCH_AUDIT", 7L, 23L));
        assertFalse(authorizationService.hasCapability(admin, "ADMIN_ACCOUNT_MANAGER", 7L, null));
    }

    @Test
    void totalAdminHasAllRegisteredCapabilitiesAcrossAllCollegeAndBatchScopes() {
        AccountPrincipal totalAdmin = principal(AccountRole.ADMIN, Collections.singletonList(
            new AccountAuthorization(7L, null, "ADMIN_ACCOUNT_MANAGER")));

        assertTrue(authorizationService.hasCapability(totalAdmin, "ADMIN_ACCOUNT_MANAGER", 999L, 888L));
        for (AdminCapabilityCode capability : AdminCapabilityCode.values()) {
            assertTrue(authorizationService.hasCapability(totalAdmin, capability.name(), 999L, 888L));
            assertTrue(totalAdmin.getAuthorities().stream()
                .anyMatch(authority -> capability.name().equals(authority.getAuthority())));
        }
        assertFalse(authorizationService.hasCapability(totalAdmin, "NOT_A_REGISTERED_CAPABILITY", 999L, 888L));
        assertFalse(totalAdmin.getAuthorities().stream().anyMatch(a -> "ROLE_STUDENT".equals(a.getAuthority())));
        assertFalse(totalAdmin.getAuthorities().stream().anyMatch(a -> "ROLE_TEACHER".equals(a.getAuthority())));
    }

    @Test
    void accountManagerCapabilityIsNotImpliedByAdminRole() {
        AccountPrincipal admin = principal(AccountRole.ADMIN,
            Collections.singletonList(new AccountAuthorization(7L, null, "BATCH_AUDIT")));
        Authentication authentication = new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities());

        assertTrue(authorizationService.hasCapability(authentication, "BATCH_AUDIT"));
        assertFalse(authorizationService.hasCapability(authentication, "ADMIN_ACCOUNT_MANAGER"));
    }

    @Test
    void nonAdminRoleCannotUseAdministratorCapability() {
        AccountPrincipal student = principal(AccountRole.STUDENT,
            Collections.singletonList(new AccountAuthorization(7L, null, "ADMIN_ACCOUNT_MANAGER")));

        assertFalse(authorizationService.hasCapability(student, "ADMIN_ACCOUNT_MANAGER", 7L, null));
    }

    private AccountPrincipal principal(AccountRole role, java.util.List<AccountAuthorization> authorizations) {
        return new AccountPrincipal(1L, "account", role, "ACTIVE", false, false,
            new Timestamp(1000L), 1L, null, authorizations);
    }
}
