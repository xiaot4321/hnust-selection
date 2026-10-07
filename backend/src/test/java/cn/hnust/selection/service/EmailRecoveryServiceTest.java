package cn.hnust.selection.service;

import cn.hnust.selection.entity.AccountEntity;
import cn.hnust.selection.exception.ApiException;
import cn.hnust.selection.repository.AccountRepository;
import cn.hnust.selection.repository.EmailRecoveryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.mockito.ArgumentCaptor;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class EmailRecoveryServiceTest {
    AccountRepository accounts;
    EmailRecoveryRepository repository;
    EmailDelivery delivery;
    BCryptPasswordEncoder encoder;
    EmailRecoveryService service;
    AccountEntity account;
    Map<String,Object> challenge;
    final String address="student@example.com";
    final String id=String.join("", java.util.Collections.nCopies(64,"a"));

    @BeforeEach void setup() {
        accounts=mock(AccountRepository.class); repository=mock(EmailRecoveryRepository.class); delivery=mock(EmailDelivery.class);
        encoder=new BCryptPasswordEncoder(4); service=new EmailRecoveryService(accounts,repository,encoder,delivery);
        account=new AccountEntity(7L,"student","STUDENT","ACTIVE",encoder.encode("old-pass"),false,null,3L);
        when(delivery.isConfigured()).thenReturn(true);
        when(accounts.findByLoginIdentifier("student")).thenReturn(Optional.of(account));
        when(accounts.findById(7L)).thenReturn(Optional.of(account));
        when(accounts.findByIdForUpdate(7L)).thenReturn(Optional.of(account));
        when(repository.email(7L)).thenReturn(address); when(repository.canSend(7L)).thenReturn(true);
        when(repository.available(eq(7L),anyString())).thenReturn(true);
        challenge=new HashMap<>(); challenge.put("account_id",7L); challenge.put("account_version",3L);
        challenge.put("purpose","RESET"); challenge.put("email",address); challenge.put("code_hash",encoder.encode("123456"));
        challenge.put("live",true); challenge.put("attempts",0); challenge.put("used_at",null);
        when(repository.challenge(id)).thenReturn(challenge);
    }
    void reset() { service.reset("student",address,id,"123456","new-pass"); }
    void rejectsWithoutPasswordWrite() {
        assertThrows(ApiException.class,this::reset); verify(accounts,never()).updatePassword(anyLong(),anyString());
    }
    @Test void missingAccountAndWrongEmailHaveSameReceiptShape() {
        when(accounts.findByLoginIdentifier("missing")).thenReturn(Optional.empty());
        Map<String,Object> unknown=service.recoveryCode("missing",address);
        Map<String,Object> mismatch=service.recoveryCode("student","other@example.com");
        assertEquals(unknown.keySet(),mismatch.keySet()); assertEquals(64,unknown.get("challengeId").toString().length());
        assertFalse(unknown.containsKey("code")); verify(delivery,never()).sendCode(anyString(),anyString(),anyBoolean());
    }
    @Test void sendsOnlyToBoundEmailAndStoresOnlyHashedCode() {
        Map<String,Object> result=service.recoveryCode("student"," STUDENT@EXAMPLE.COM ");
        ArgumentCaptor<String> code=ArgumentCaptor.forClass(String.class);
        verify(delivery).sendCode(eq(address),code.capture(),eq(false)); assertTrue(code.getValue().matches("[0-9]{6}"));
        ArgumentCaptor<String> hash=ArgumentCaptor.forClass(String.class);
        verify(repository).issue(eq((String)result.get("challengeId")),eq(7L),eq(3L),eq("RESET"),eq(address),hash.capture());
        assertTrue(encoder.matches(code.getValue(),hash.getValue())); assertFalse(result.containsKey("code"));
    }
    @Test void wrongCodeConsumesAnAttemptWithoutChangingPassword() {
        assertThrows(ApiException.class,()->service.reset("student",address,id,"999999","new-pass"));
        verify(repository).failedAttempt(id); verify(accounts,never()).updatePassword(anyLong(),anyString());
    }
    @Test void expiredCodeCannotReset() { challenge.put("live",false); rejectsWithoutPasswordWrite(); }
    @Test void usedCodeCannotReset() { challenge.put("used_at",new java.sql.Timestamp(0)); rejectsWithoutPasswordWrite(); }
    @Test void exhaustedAttemptsCannotReset() { challenge.put("attempts",5); rejectsWithoutPasswordWrite(); }
    @Test void anotherAccountsCodeCannotReset() { challenge.put("account_id",8L); rejectsWithoutPasswordWrite(); }
    @Test void bindingCodeCannotResetPassword() { challenge.put("purpose","BIND"); rejectsWithoutPasswordWrite(); }
    @Test void passwordChangeInvalidatesPreviouslyIssuedCode() { challenge.put("account_version",2L); rejectsWithoutPasswordWrite(); }
    @Test void changedBoundEmailInvalidatesPreviouslyIssuedCode() { when(repository.email(7L)).thenReturn("new@example.com"); rejectsWithoutPasswordWrite(); }
    @Test void validResetHashesPasswordInvalidatesCodesAndAudits() {
        reset(); ArgumentCaptor<String> hash=ArgumentCaptor.forClass(String.class);
        verify(accounts).updatePassword(eq(7L),hash.capture()); assertTrue(encoder.matches("new-pass",hash.getValue()));
        verify(repository).revokeTemporary(7L); verify(repository).invalidate(7L); verify(repository).audit(7L,"EMAIL_PASSWORD_RESET");
    }
    @Test void bindingRequiresCurrentPassword() {
        assertThrows(ApiException.class,()->service.bindingCode(7L,address,"incorrect"));
        verify(delivery,never()).sendCode(anyString(),anyString(),anyBoolean());
    }
    @Test void verifiedBindingConsumesCodesAndAudits() {
        challenge.put("purpose","BIND"); service.confirmBinding(7L,id,"123456");
        verify(repository).bind(7L,address); verify(repository).invalidate(7L); verify(repository).audit(7L,"EMAIL_BOUND");
    }
    @Test void cooldownPreventsSendingAdditionalMail() {
        when(repository.canSend(7L)).thenReturn(false);
        assertThrows(ApiException.class,()->service.bindingCode(7L,address,"old-pass"));
        service.recoveryCode("student",address); verify(delivery,never()).sendCode(anyString(),anyString(),anyBoolean());
    }
    @Test void administratorsDoNotUseStudentTeacherRecovery() {
        account=new AccountEntity(7L,"student","ADMIN","ACTIVE",encoder.encode("old-pass"),false,null,3L);
        when(accounts.findByIdForUpdate(7L)).thenReturn(Optional.of(account));
        service.recoveryCode("student",address); rejectsWithoutPasswordWrite();
        verify(delivery,never()).sendCode(anyString(),anyString(),anyBoolean());
    }
    @Test void noMailConfigurationNeverProducesFakeCode() {
        when(delivery.isConfigured()).thenReturn(false);
        assertThrows(ApiException.class,()->service.recoveryCode("student",address));
        verify(repository,never()).issue(anyString(),anyLong(),anyLong(),anyString(),anyString(),anyString());
    }
    @Test void utf8PasswordsLongerThanBcryptLimitAreRejected() {
        assertThrows(ApiException.class,()->service.reset("student",address,id,"123456",String.join("",java.util.Collections.nCopies(25,"学"))));
        verify(accounts,never()).updatePassword(anyLong(),anyString());
    }
}
