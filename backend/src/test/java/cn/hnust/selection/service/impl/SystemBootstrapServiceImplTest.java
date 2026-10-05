package cn.hnust.selection.service.impl;

import cn.hnust.selection.bootstrap.InitialAdminBootstrapResult;
import cn.hnust.selection.repository.SystemBootstrapRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证一次性总管理员初始化的服务边界，不连接或修改真实数据库。 */
@ExtendWith(MockitoExtension.class)
class SystemBootstrapServiceImplTest {
    @Mock
    private SystemBootstrapRepository repository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private SystemBootstrapServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SystemBootstrapServiceImpl(repository, passwordEncoder, new ObjectMapper());
    }

    @Test
    void initializesAdminWithTemporaryCodeAndStoresOnlyEncodedCredential() {
        when(repository.hasReservedAdminManagerSlot()).thenReturn(false);
        when(repository.loginIdentifierExists("admin")).thenReturn(false);
        when(repository.collegeCodeExists(anyString())).thenReturn(false);
        when(repository.findCollegeByCode(anyString())).thenReturn(Optional.empty());
        when(repository.collegeNameExists("计算机科学与工程学院")).thenReturn(false);
        when(repository.insertCollege(anyString(), eq("计算机科学与工程学院"))).thenReturn(41L);
        when(repository.insertBootstrapOperation(eq(41L), anyString(), anyString())).thenReturn(51L);
        when(repository.insertInitialAdminAccount("admin")).thenReturn(61L);
        when(repository.insertInitialAdminManagerAuthorization(61L, 41L)).thenReturn(71L);
        when(repository.insertShownTemporaryCredential(eq(61L), anyString(), eq(51L))).thenReturn(81L);
        when(passwordEncoder.encode(anyString())).thenReturn("bcrypt-hash-only");

        InitialAdminBootstrapResult result = service.initializeInitialAdmin(
            " admin ", null, " 计算机科学与工程学院 ");

        assertEquals("admin", result.getLoginIdentifier());
        assertTrue(result.getCollegeCode().matches("TMP-[0-9A-F]{24}"));
        assertEquals("计算机科学与工程学院", result.getCollegeName());
        assertEquals(61L, result.getAccountId().longValue());
        assertTrue(result.getTemporaryCredential().length() >= 40);
        assertFalse(result.toString().contains(result.getTemporaryCredential()),
            "调试输出必须隐藏临时凭证明文");

        ArgumentCaptor<String> storedHash = ArgumentCaptor.forClass(String.class);
        verify(repository).insertShownTemporaryCredential(eq(61L), storedHash.capture(), eq(51L));
        assertEquals("bcrypt-hash-only", storedHash.getValue());
        assertNotEquals(result.getTemporaryCredential(), storedHash.getValue());
        verify(passwordEncoder).encode(result.getTemporaryCredential());

        ArgumentCaptor<String> credentialAuditSnapshot = ArgumentCaptor.forClass(String.class);
        verify(repository).insertAuditEvent(eq(51L), eq("TEMPORARY_CREDENTIAL"), eq(81L),
            eq("TEMPORARY_CREDENTIAL_ISSUED_AND_DISPLAY_RESERVED"), eq(41L), isNull(),
            credentialAuditSnapshot.capture(), anyString());
        assertTrue(credentialAuditSnapshot.getValue().contains("\"expiresAt\":null"),
            "总管理员初始化凭证应在审计元数据中明确记录为无到期时间");
        assertTrue(credentialAuditSnapshot.getValue().contains("\"singleUse\":true"),
            "取消到期时间后仍应保留一次性使用限制");
        assertFalse(credentialAuditSnapshot.getValue().contains(result.getTemporaryCredential()));
        assertFalse(credentialAuditSnapshot.getValue().contains("bcrypt-hash-only"));
        verify(repository).completeBootstrapOperation(51L);
    }

    @Test
    void refusesBootstrapWhenReservedManagerSlotAlreadyExists() {
        when(repository.hasReservedAdminManagerSlot()).thenReturn(true);

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> service.initializeInitialAdmin("admin", null, "计算机科学与工程学院"));

        assertTrue(error.getMessage().contains("TODO-24"));
        verify(repository, never()).insertCollege(anyString(), anyString());
        verify(repository, never()).insertInitialAdminAccount(anyString());
    }

    @Test
    void refusesExistingLoginIdentifierBeforeCreatingCollege() {
        when(repository.hasReservedAdminManagerSlot()).thenReturn(false);
        when(repository.loginIdentifierExists("admin")).thenReturn(true);

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> service.initializeInitialAdmin("admin", null, "计算机科学与工程学院"));

        assertTrue(error.getMessage().contains("已存在"));
        verify(repository, never()).insertCollege(anyString(), anyString());
        verify(repository, never()).insertInitialAdminAccount(anyString());
    }
}
