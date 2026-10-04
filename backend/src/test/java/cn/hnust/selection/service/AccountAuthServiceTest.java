package cn.hnust.selection.service;

import cn.hnust.selection.enums.AccountRole;
import cn.hnust.selection.entity.TemporaryCredentialEntity;
import cn.hnust.selection.exception.ApiException;
import cn.hnust.selection.repository.AccountRepository;
import cn.hnust.selection.entity.AccountEntity;
import cn.hnust.selection.service.impl.AccountAuthServiceImpl;
import cn.hnust.selection.security.AccountIdentity;
import cn.hnust.selection.security.AccountPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

/** 认证服务单元测试：覆盖正式密码、错误/停用账号、临时凭证和改密边界。 */
class AccountAuthServiceTest {
    private AccountRepository accountRepository;
    private PasswordEncoder passwordEncoder;
    private AccountAuthService service;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        passwordEncoder = new BCryptPasswordEncoder(4);
        service = new AccountAuthServiceImpl(accountRepository, passwordEncoder);
    }

    @Test
    void authenticatesAnActiveAccountWithItsCurrentPassword() {
        AccountEntity account = account(8L, "s2026001", "ACTIVE", passwordEncoder.encode("current-pass"), false, 2L);
        AccountPrincipal principal = principal(account, false, false);
        when(accountRepository.findByLoginIdentifier("s2026001")).thenReturn(Optional.of(account));
        when(accountRepository.toPrincipal(account, false)).thenReturn(principal);

        AccountPrincipal result = service.authenticate("s2026001", "current-pass");

        assertEquals(AccountRole.STUDENT, result.getRole());
        assertFalse(result.isTemporaryCredentialLogin());
        verify(accountRepository).recordSuccessfulLogin(8L);
    }

    @Test
    void returnsTheSameCredentialFailureForUnknownAndWrongPasswords() {
        when(accountRepository.findByLoginIdentifier("unknown")).thenReturn(Optional.empty());
        AccountEntity account = account(9L, "known", "ACTIVE", passwordEncoder.encode("right"), false, 0L);
        when(accountRepository.findByLoginIdentifier("known")).thenReturn(Optional.of(account));

        assertEquals("Invalid credentials",
            assertThrows(BadCredentialsException.class, () -> service.authenticate("unknown", "wrong")).getMessage());
        assertEquals("Invalid credentials",
            assertThrows(BadCredentialsException.class, () -> service.authenticate("known", "wrong")).getMessage());
    }

    @Test
    void refusesDisabledAccounts() {
        AccountEntity account = account(10L, "disabled", "DISABLED", passwordEncoder.encode("pass"), false, 0L);
        when(accountRepository.findByLoginIdentifier("disabled")).thenReturn(Optional.of(account));

        assertThrows(DisabledException.class, () -> service.authenticate("disabled", "pass"));
    }

    @Test
    void acceptsAnUnusedUnexpiredTemporaryCredentialOnlyForForcedPasswordChange() {
        AccountEntity account = account(11L, "temporary", "ACTIVE", passwordEncoder.encode("old"), true, 5L);
        TemporaryCredentialEntity temporary = new TemporaryCredentialEntity(31L, passwordEncoder.encode("one-time"),
            false, false, true);
        AccountPrincipal principal = principal(account, true, true);
        when(accountRepository.findByLoginIdentifier("temporary")).thenReturn(Optional.of(account));
        when(accountRepository.findValidTemporaryCredentials(11L)).thenReturn(Collections.singletonList(temporary));
        when(accountRepository.toPrincipal(account, true)).thenReturn(principal);

        AccountPrincipal result = service.authenticate("temporary", "one-time");

        assertTrue(result.isMustChangePassword());
        assertTrue(result.isTemporaryCredentialLogin());
        verify(accountRepository).recordSuccessfulLogin(11L);
    }

    @Test
    void consumesTemporaryCredentialAndUpdatesPasswordInTheSameServiceTransaction() {
        AccountEntity existing = account(12L, "first-login", "ACTIVE", null, true, 3L);
        AccountEntity updated = account(12L, "first-login", "ACTIVE", "new-hash", false, 4L);
        TemporaryCredentialEntity temporary = new TemporaryCredentialEntity(44L, passwordEncoder.encode("issued-once"),
            false, false, true);
        AccountPrincipal updatedPrincipal = principal(updated, false, false);
        when(accountRepository.findByIdForUpdate(12L)).thenReturn(Optional.of(existing));
        when(accountRepository.findTemporaryCredentials(12L, true)).thenReturn(Collections.singletonList(temporary));
        when(accountRepository.consumeTemporaryCredential(44L)).thenReturn(true);
        when(accountRepository.findById(12L)).thenReturn(Optional.of(updated));
        when(accountRepository.toPrincipal(updated, false)).thenReturn(updatedPrincipal);

        // 验证更新写入的是哈希，而不是明文；临时凭证消费与密码更新都必须发生。
        AccountPrincipal result = service.changePassword(principal(existing, true, true),
            "issued-once", "new-permanent-password");

        ArgumentCaptor<String> encodedPassword = ArgumentCaptor.forClass(String.class);
        verify(accountRepository).consumeTemporaryCredential(44L);
        verify(accountRepository).updatePassword(eq(12L), encodedPassword.capture());
        assertTrue(passwordEncoder.matches("new-permanent-password", encodedPassword.getValue()));
        assertFalse(result.isMustChangePassword());
    }

    @Test
    void rejectsTemporaryCredentialThatWasAlreadyConsumed() {
        AccountEntity account = account(13L, "first-login", "ACTIVE", null, true, 3L);
        TemporaryCredentialEntity used = new TemporaryCredentialEntity(45L, passwordEncoder.encode("issued-once"),
            true, false, true);
        when(accountRepository.findByIdForUpdate(13L)).thenReturn(Optional.of(account));
        when(accountRepository.findTemporaryCredentials(13L, true)).thenReturn(Collections.singletonList(used));

        ApiException exception = assertThrows(ApiException.class,
            () -> service.changePassword(principal(account, true, true), "issued-once", "new-permanent-password"));
        assertEquals("TEMP_CREDENTIAL_ALREADY_USED", exception.getCode());
    }

    @Test
    void rejectsPasswordsThatWouldBeTruncatedByBcrypt() {
        AccountEntity account = account(14L, "first-login", "ACTIVE", null, true, 3L);
        ApiException exception = assertThrows(ApiException.class,
            () -> service.changePassword(principal(account, true, true), "", "中中中中中中中中中中中中中中中中中中中中中中中中中"));
        assertEquals("INVALID_ARGUMENT", exception.getCode());
    }

    private AccountEntity account(Long id, String identifier, String status,
                                  String passwordHash, boolean mustChange, long version) {
        return new AccountEntity(id, identifier, "STUDENT", status, passwordHash,
            mustChange, new Timestamp(1000L), version);
    }

    private AccountPrincipal principal(AccountEntity account, boolean mustChange, boolean temporary) {
        return new AccountPrincipal(account.getId(), account.getLoginIdentifier(), AccountRole.STUDENT,
            account.getAccountStatus(), mustChange, temporary, account.getCredentialChangedAt(),
            account.getRowVersion(), new AccountIdentity(2L, "测试学生", "s2026001", 1L),
            Collections.emptyList());
    }
}
