package cn.hnust.selection.service.impl;

import cn.hnust.selection.entity.PersonnelImportEntity;
import cn.hnust.selection.entity.PersonnelImportRowEntity;
import cn.hnust.selection.repository.PersonnelManagementRepository;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.PersonnelAccessService;
import cn.hnust.selection.service.PersonnelRegistrationService;
import cn.hnust.selection.service.PersonnelRegistrationService.CredentialResult;
import cn.hnust.selection.service.PersonnelRegistrationService.RegisteredPerson;
import cn.hnust.selection.service.PrivateFileStorage;
import cn.hnust.selection.service.PrivateFileStorage.StoredPrivateFile;
import cn.hnust.selection.vo.PersonnelImportRowVO;
import cn.hnust.selection.vo.PersonnelImportVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 名单导入 Service 的用例测试，验证首次凭证回执和历史查询脱敏规则。 */
class PersonnelImportServiceImplTest {
    private static final Long ACTOR_ID = 9L;
    private static final Long COLLEGE_ID = 7L;
    private static final Long ACADEMIC_YEAR_ID = 2026L;
    private static final String IDEMPOTENCY_KEY = "00000000-0000-4000-8000-000000000001";

    private PersonnelManagementRepository repository;
    private PersonnelAccessService accessService;
    private PersonnelRegistrationService registrationService;
    private PrivateFileStorage privateFileStorage;
    private PersonnelImportServiceImpl service;
    private AccountPrincipal actor;

    @BeforeEach
    void setUp() {
        repository = mock(PersonnelManagementRepository.class);
        accessService = mock(PersonnelAccessService.class);
        registrationService = mock(PersonnelRegistrationService.class);
        privateFileStorage = mock(PrivateFileStorage.class);
        service = new PersonnelImportServiceImpl(repository, accessService, registrationService,
            privateFileStorage, new ObjectMapper());
        actor = mock(AccountPrincipal.class);
        when(actor.getAccountId()).thenReturn(ACTOR_ID);
        when(accessService.requireCollege(actor, COLLEGE_ID)).thenReturn(actor);
    }

    @Test
    void importsTeacherAndReturnsNewTemporaryCredentialInInitialResponse() throws Exception {
        String csv = "loginIdentifier,employeeNo,fullName,eligibilityStatus,evidenceType,evidenceReference\n"
            + "E001,E001,王老师,ELIGIBLE,APPOINTMENT,2026-001\n";
        MockMultipartFile file = new MockMultipartFile("file", "teachers.csv", "text/csv",
            csv.getBytes(StandardCharsets.UTF_8));
        StoredPrivateFile storedFile = new StoredPrivateFile("storage-key", "teachers.csv", "text/csv",
            file.getSize(), "content-digest", Paths.get("private", "teachers.csv"));

        when(repository.academicYearExists(ACADEMIC_YEAR_ID)).thenReturn(true);
        when(repository.findOperation(ACTOR_ID, "PERSONNEL_IMPORT", IDEMPOTENCY_KEY))
            .thenReturn(Optional.empty());
        when(repository.insertOperation(eq(ACTOR_ID), eq("PERSONNEL_IMPORT"), eq(COLLEGE_ID),
            eq(IDEMPOTENCY_KEY), anyString())).thenReturn(41L);
        when(privateFileStorage.store(file)).thenReturn(storedFile);
        when(repository.managedFileId("storage-key")).thenReturn(55L);
        when(repository.insertImport(ACTOR_ID, COLLEGE_ID, ACADEMIC_YEAR_ID, "TEACHER", 55L, 41L))
            .thenReturn(77L);
        when(repository.personIdentifierExists("TEACHER", "E001")).thenReturn(false);
        when(repository.loginIdentifierExists("E001")).thenReturn(false);
        when(registrationService.createTeacher(eq(actor), eq(41L), any(), any()))
            .thenReturn(new RegisteredPerson("TEACHER", 88L, 99L, "E001",
                new CredentialResult("temporary-once", "2026-10-07T09:00:00Z"), 111L));

        PersonnelImportVO response = service.importPersonnel(actor, COLLEGE_ID, ACADEMIC_YEAR_ID,
            "TEACHER", file, IDEMPOTENCY_KEY);

        assertEquals("COMPLETED", response.getStatus());
        assertEquals(1, response.getAcceptedCount());
        assertEquals(0, response.getRejectedCount());
        assertEquals(1, response.getRows().size());
        assertEquals("temporary-once", response.getRows().get(0).getTemporaryCredential());
        verify(repository).completeOperation(41L);
    }

    @Test
    void historicalRowsDoNotRestoreOneTimeCredentials() {
        when(repository.findImport(77L)).thenReturn(Optional.of(
            new PersonnelImportEntity(77L, COLLEGE_ID, "TEACHER", "COMPLETED", 1, 0)));
        when(repository.listImportRows(77L)).thenReturn(Arrays.asList(
            new PersonnelImportRowEntity(2, "E001", "CREATED", null, null, 88L, 111L)));

        java.util.List<PersonnelImportRowVO> rows = service.getImportRows(actor, 77L);

        assertEquals(1, rows.size());
        assertNull(rows.get(0).getLoginIdentifier());
        assertNull(rows.get(0).getTemporaryCredential());
        verify(accessService).requireCollege(actor, COLLEGE_ID);
    }
}
