package cn.hnust.selection.service.impl;

import cn.hnust.selection.enums.AccountRole;
import cn.hnust.selection.exception.ApiException;
import cn.hnust.selection.repository.TeacherWorkspaceRepository;
import cn.hnust.selection.request.UpdateTeacherProfileRequest;
import cn.hnust.selection.security.AccountIdentity;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.vo.TeacherProfileVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TeacherWorkspaceServiceImplTest {
    private TeacherWorkspaceRepository repository;
    private TeacherWorkspaceServiceImpl service;
    private AccountPrincipal actor;
    private TeacherProfileVO profile;
    @BeforeEach void setup() {
        repository = mock(TeacherWorkspaceRepository.class);
        service = new TeacherWorkspaceServiceImpl(repository, mock(PlatformTransactionManager.class), mock(TaskExecutor.class));
        actor = mock(AccountPrincipal.class);
        when(actor.getRole()).thenReturn(AccountRole.TEACHER);
        when(actor.getAccountId()).thenReturn(10L);
        when(actor.getIdentity()).thenReturn(new AccountIdentity(1L, "导师", "tea001", 1L));
        when(repository.lockTeacher(1L, 10L)).thenReturn(true);
        profile = new TeacherProfileVO(); profile.setVersionNo(1); profile.setEtag("current");
        when(repository.findProfile(1L)).thenReturn(Optional.of(profile));
    }
    @ParameterizedTest @ValueSource(strings = {"current", "stale"})
    void pendingReviewCanBeEditedWithCurrentEtag(String etag) {
        profile.setReviewStatus("PENDING_REVIEW");
        profile.setProfileRowVersion(Long.valueOf(5L));
        profile.setSubmittedResearchDirections("旧研究方向");
        UpdateTeacherProfileRequest request = new UpdateTeacherProfileRequest();
        if ("stale".equals(etag)) {
            ApiException failure = assertThrows(ApiException.class, () -> service.updateProfile(actor, etag, request));
            assertEquals("PRECONDITION_FAILED", failure.getCode());
            verify(repository, never()).updatePendingProfile(anyLong(), anyInt(), anyLong(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString());
            verify(repository, never()).insertOperation(anyLong(), any(), anyString(), anyString(), anyString());
        } else {
            when(repository.updatePendingProfile(1L, 1, 5L, "", "", "", "", "", "")).thenReturn(1);
            service.updateProfile(actor, etag, request);
            verify(repository).updatePendingProfile(1L, 1, 5L, "", "", "", "", "", "");
            verify(repository, never()).insertProfileSubmission(anyLong(), anyInt(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString());
            verify(repository).insertOperation(eq(10L), isNull(), eq("TEACHER_PROFILE_UPDATE_PENDING"), anyString(), anyString());
            verify(repository).insertAudit(eq(10L), isNull(), eq("TEACHER_PUBLIC_PROFILE_VERSION"), eq(1L),
                eq("TEACHER_PROFILE_UPDATE_PENDING"), anyString(), anyString(), anyLong());
        }
    }
    @ParameterizedTest @ValueSource(strings = {"DRAFT", "REJECTED", "APPROVED"})
    void reviewedOrDraftProfileCanSubmit(String status) {
        profile.setReviewStatus(status);
        service.updateProfile(actor, "current", new UpdateTeacherProfileRequest());
        if ("DRAFT".equals(status)) verify(repository).updateDraftProfile(1L, 1, "", "", "", "", "", "");
        else verify(repository).insertProfileSubmission(1L, 2, "", "", "", "", "", "");
    }
}
