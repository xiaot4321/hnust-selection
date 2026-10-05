package cn.hnust.selection.service.impl;

import cn.hnust.selection.common.PageResult;
import cn.hnust.selection.enums.AccountRole;
import cn.hnust.selection.exception.ApiException;
import cn.hnust.selection.repository.StudentSelectionQueryRepository;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.StudentSelectionQueryService;
import cn.hnust.selection.vo.PreferenceSubmissionVO;
import cn.hnust.selection.vo.StudentPreferencesVO;
import cn.hnust.selection.vo.SupplementApplicationVO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.Optional;

@Service
public class StudentSelectionQueryServiceImpl implements StudentSelectionQueryService {
    private final StudentSelectionQueryRepository repository;

    public StudentSelectionQueryServiceImpl(StudentSelectionQueryRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public StudentPreferencesVO getCurrentPreferences(AccountPrincipal actor, Long batchId) {
        Long studentId = requireStudent(actor);
        return repository.findCurrentPreferences(studentId, actor.getAccountId(), batchId);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<PreferenceSubmissionVO> listPreferenceSubmissions(AccountPrincipal actor, Long batchId,
                                                                         int pageNo, int pageSize) {
        Long batchStudentId = batchStudentId(actor, batchId);
        if (!batchStudentIdPresent(batchStudentId)) {
            return new PageResult<PreferenceSubmissionVO>(Collections.<PreferenceSubmissionVO>emptyList(), 0L, pageNo, pageSize);
        }
        return new PageResult<PreferenceSubmissionVO>(repository.listSubmissions(batchStudentId, pageNo, pageSize),
            repository.countSubmissions(batchStudentId), pageNo, pageSize);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<SupplementApplicationVO> listSupplementApplications(AccountPrincipal actor, Long batchId,
                                                                            int pageNo, int pageSize) {
        Long batchStudentId = batchStudentId(actor, batchId);
        if (!batchStudentIdPresent(batchStudentId)) {
            return new PageResult<SupplementApplicationVO>(Collections.<SupplementApplicationVO>emptyList(), 0L, pageNo, pageSize);
        }
        return new PageResult<SupplementApplicationVO>(repository.listSupplementApplications(batchStudentId, pageNo, pageSize),
            repository.countSupplementApplications(batchStudentId), pageNo, pageSize);
    }

    private Long batchStudentId(AccountPrincipal actor, Long batchId) {
        Long studentId = requireStudent(actor);
        Optional<Long> id = repository.findBatchStudentId(studentId, actor.getAccountId(), batchId);
        return id.orElse(null);
    }

    private static boolean batchStudentIdPresent(Long id) { return id != null; }

    private static Long requireStudent(AccountPrincipal actor) {
        if (actor == null || actor.getRole() != AccountRole.STUDENT || actor.getAccountId() == null
            || actor.getIdentity() == null || actor.getIdentity().getId() == null) {
            throw new ApiException("FORBIDDEN", "仅学生本人可查询本人志愿和补选记录", HttpStatus.FORBIDDEN);
        }
        return actor.getIdentity().getId();
    }
}
