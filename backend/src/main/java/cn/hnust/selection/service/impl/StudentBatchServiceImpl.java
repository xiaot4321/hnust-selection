package cn.hnust.selection.service.impl;

import cn.hnust.selection.common.PageResult;
import cn.hnust.selection.enums.AccountRole;
import cn.hnust.selection.exception.ApiException;
import cn.hnust.selection.repository.StudentBatchRepository;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.StudentBatchService;
import cn.hnust.selection.vo.StudentBatchSummaryVO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StudentBatchServiceImpl implements StudentBatchService {
    private final StudentBatchRepository repository;

    public StudentBatchServiceImpl(StudentBatchRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<StudentBatchSummaryVO> listOwnBatches(AccountPrincipal actor, int pageNo, int pageSize) {
        if (actor == null || actor.getRole() != AccountRole.STUDENT || actor.getAccountId() == null
            || actor.getIdentity() == null || actor.getIdentity().getId() == null) {
            throw new ApiException("FORBIDDEN", "仅学生本人可查询本人批次", HttpStatus.FORBIDDEN);
        }
        Long studentId = actor.getIdentity().getId();
        Long accountId = actor.getAccountId();
        return new PageResult<StudentBatchSummaryVO>(
            repository.findVisibleBatches(studentId, accountId, pageNo, pageSize),
            repository.countVisibleBatches(studentId, accountId), pageNo, pageSize);
    }
}
