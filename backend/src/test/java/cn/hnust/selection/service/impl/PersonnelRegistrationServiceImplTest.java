package cn.hnust.selection.service.impl;

import cn.hnust.selection.exception.ApiException;
import cn.hnust.selection.entity.CollegeEntity;
import cn.hnust.selection.entity.MajorEntity;
import cn.hnust.selection.repository.PersonnelManagementRepository;
import cn.hnust.selection.request.CreateStudentRequest;
import cn.hnust.selection.request.CreateTeacherRequest;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.PersonnelRegistrationService.RegisteredPerson;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 人员建档测试：验证身份编号规则，以及 TODO-58 的初始密码来源和安全哈希持久化。 */
class PersonnelRegistrationServiceImplTest {
    private final PersonnelManagementRepository repository = mock(PersonnelManagementRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final PersonnelRegistrationServiceImpl service = new PersonnelRegistrationServiceImpl(
        repository, passwordEncoder, new ObjectMapper());

    @Test
    void studentLoginIdentifierMustEqualStudentNumberBeforeAnyDatabaseWork() {
        CreateStudentRequest request = new CreateStudentRequest();
        request.setLoginIdentifier("different-login");
        request.setStudentNo("S20260001");

        ApiException error = assertThrows(ApiException.class,
            () -> service.createStudent(null, 100L, request, null));

        assertEquals("INVALID_ARGUMENT", error.getCode());
        verifyNoInteractions(repository);
    }

    @Test
    void teacherLoginIdentifierMustEqualEmployeeNumberBeforeAnyDatabaseWork() {
        CreateTeacherRequest request = new CreateTeacherRequest();
        request.setLoginIdentifier("different-login");
        request.setEmployeeNo("T0001");

        ApiException error = assertThrows(ApiException.class,
            () -> service.createTeacher(null, 100L, request, null));

        assertEquals("INVALID_ARGUMENT", error.getCode());
        verifyNoInteractions(repository);
    }

    @Test
    void studentInitialPasswordUsesLastSixStudentNumberCharacters() {
        stubStudentRegistration("S2026123456", "123456");
        AccountPrincipal actor = mock(AccountPrincipal.class);
        when(actor.getAccountId()).thenReturn(99L);

        RegisteredPerson created = service.createStudent(actor, 100L,
            studentRequest("S2026123456"), null);

        assertEquals("123456", created.getCredential().getPlaintext());
        assertNull(created.getCredential().getExpiresAt());
        verify(passwordEncoder).encode("123456");
        verify(repository).insertTemporaryCredential(10L, "bcrypt:123456", 100L);
        ArgumentCaptor<String> auditValues = ArgumentCaptor.forClass(String.class);
        verify(repository).insertAudit(eq(100L), eq(99L), eq(1L), eq("STUDENT"), eq(20L),
            eq("STUDENT_ACCOUNT_CREATED"), auditValues.capture(), eq("学院管理员创建学生账号与初始档案"));
        assertTrue(auditValues.getValue().contains("STUDENT_NO_SUFFIX_6"));
        assertFalse(auditValues.getValue().contains("S2026123456"));
    }

    @Test
    void teacherInitialPasswordUsesLastSixEmployeeNumberCharacters() {
        stubTeacherRegistration("E2026123456", "123456");

        RegisteredPerson created = service.createTeacher(mockActor(), 101L,
            teacherRequest("E2026123456"), null);

        assertEquals("123456", created.getCredential().getPlaintext());
        assertNull(created.getCredential().getExpiresAt());
        verify(passwordEncoder).encode("123456");
        verify(repository).insertTemporaryCredential(11L, "bcrypt:123456", 101L);
    }

    @Test
    void shortTeacherEmployeeNumberIsUsedInFullWithoutPadding() {
        stubTeacherRegistration("T0001", "T0001");

        RegisteredPerson created = service.createTeacher(mockActor(), 102L,
            teacherRequest("T0001"), null);

        assertEquals("T0001", created.getCredential().getPlaintext());
        assertNull(created.getCredential().getExpiresAt());
        verify(passwordEncoder).encode("T0001");
        verify(repository).insertTemporaryCredential(11L, "bcrypt:T0001", 102L);
    }

    private void stubStudentRegistration(String studentNo, String initialPassword) {
        when(repository.loginIdentifierExists(studentNo)).thenReturn(false);
        when(repository.personIdentifierExists("STUDENT", studentNo)).thenReturn(false);
        when(repository.findCollege(1L)).thenReturn(Optional.of(new CollegeEntity(1L, "CS", "计算机学院", true)));
        when(repository.findMajorByCode(1L, "CS-01")).thenReturn(Optional.of(
            new MajorEntity(30L, 1L, "CS-01", "计算机科学与技术", true, null, null, 1L)));
        when(repository.insertAccount(studentNo, "STUDENT")).thenReturn(10L);
        when(repository.insertStudent(10L, studentNo, "张同学", 1L, 30L, "ACADEMIC_MASTER", "2026"))
            .thenReturn(20L);
        when(repository.insertTemporaryCredential(10L, "bcrypt:" + initialPassword, 100L)).thenReturn(40L);
        when(repository.credentialExpiry(40L)).thenReturn(null);
        when(passwordEncoder.encode(initialPassword)).thenReturn("bcrypt:" + initialPassword);
    }

    private void stubTeacherRegistration(String employeeNo, String initialPassword) {
        when(repository.loginIdentifierExists(employeeNo)).thenReturn(false);
        when(repository.personIdentifierExists("TEACHER", employeeNo)).thenReturn(false);
        when(repository.findCollege(1L)).thenReturn(Optional.of(new CollegeEntity(1L, "CS", "计算机学院", true)));
        when(repository.insertAccount(employeeNo, "TEACHER")).thenReturn(11L);
        when(repository.insertTeacher(11L, employeeNo, "李老师", 1L)).thenReturn(21L);
        when(repository.insertInitialTeacherProfile(21L)).thenReturn(31L);
        when(repository.insertTemporaryCredential(11L, "bcrypt:" + initialPassword, 101L)).thenReturn(41L);
        when(repository.credentialExpiry(41L)).thenReturn(null);
        when(repository.insertTemporaryCredential(11L, "bcrypt:" + initialPassword, 102L)).thenReturn(42L);
        when(repository.credentialExpiry(42L)).thenReturn(null);
        when(passwordEncoder.encode(initialPassword)).thenReturn("bcrypt:" + initialPassword);
    }

    private CreateStudentRequest studentRequest(String studentNo) {
        CreateStudentRequest request = new CreateStudentRequest();
        request.setLoginIdentifier(studentNo);
        request.setStudentNo(studentNo);
        request.setFullName("张同学");
        request.setCollegeId(1L);
        request.setMajorCode("CS-01");
        request.setDegreeType("ACADEMIC_MASTER");
        request.setEnrollmentYearCode("2026");
        request.setClassificationBasis("2026 级录取名单");
        request.setClassificationReason("导入初始学生身份分类");
        return request;
    }

    private AccountPrincipal mockActor() {
        AccountPrincipal actor = mock(AccountPrincipal.class);
        when(actor.getAccountId()).thenReturn(99L);
        return actor;
    }

    private CreateTeacherRequest teacherRequest(String employeeNo) {
        CreateTeacherRequest request = new CreateTeacherRequest();
        request.setLoginIdentifier(employeeNo);
        request.setEmployeeNo(employeeNo);
        request.setFullName("李老师");
        request.setCollegeId(1L);
        return request;
    }
}
